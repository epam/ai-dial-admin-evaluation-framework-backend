package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.validation.RevalidationProperties;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteMapper;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Clones a test suite's execution-time configuration. Test cases are NOT copied — they remain
 * owned by the dataset shared between the source and clone (or a different dataset if the user
 * overrides {@code datasetId}). Suite-level DIAL files referenced by {@code requestTemplate},
 * {@code inputBindings}, {@code argumentTemplate}, and TSMD bindings are copied to the new
 * suite's bucket; test-case-scoped files are not in scope. Post-clone validation is synchronous
 * only — no async {@code RevalidationTask} is spawned.
 */
@Slf4j
@Service
@LogExecution
public class TestSuiteCloneService {

    private final TestSuiteRepository testSuiteRepository;
    private final TestSuiteMetricDefinitionRepository testSuiteMetricDefinitionRepository;
    private final DatasetRepository datasetRepository;
    private final FileService fileService;
    private final TestSuiteMapper testSuiteMapper;
    private final SuiteValidationService suiteValidationService;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final AuthorResolver authorResolver;
    private final RevalidationProperties revalidationProperties;
    private final Clock clock;
    private final EndpointSchemaRefResolver endpointSchemaRefResolver;
    private final ValidationWarningsSerializer warningsSerializer;
    private final TransactionTemplate transactionTemplate;

    public TestSuiteCloneService(
            TestSuiteRepository testSuiteRepository,
            TestSuiteMetricDefinitionRepository testSuiteMetricDefinitionRepository,
            DatasetRepository datasetRepository,
            FileService fileService,
            TestSuiteMapper testSuiteMapper,
            SuiteValidationService suiteValidationService,
            DatasetSchemaProvider datasetSchemaProvider,
            AuthorResolver authorResolver,
            RevalidationProperties revalidationProperties,
            @Qualifier("metaTransactionManager") PlatformTransactionManager transactionManager,
            Clock clock,
            EndpointSchemaRefResolver endpointSchemaRefResolver,
            ValidationWarningsSerializer warningsSerializer) {
        this.testSuiteRepository = testSuiteRepository;
        this.testSuiteMetricDefinitionRepository = testSuiteMetricDefinitionRepository;
        this.datasetRepository = datasetRepository;
        this.fileService = fileService;
        this.testSuiteMapper = testSuiteMapper;
        this.suiteValidationService = suiteValidationService;
        this.datasetSchemaProvider = datasetSchemaProvider;
        this.authorResolver = authorResolver;
        this.revalidationProperties = revalidationProperties;
        this.clock = clock;
        this.endpointSchemaRefResolver = endpointSchemaRefResolver;
        this.warningsSerializer = warningsSerializer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Clones a test suite's configuration and TSMDs. Test cases are not copied — the cloned suite
     * references the same dataset (or {@code dto.datasetId} when supplied). Validation runs
     * synchronously; the response's {@code revalidationTask} is always {@code null}.
     */
    public TestSuiteUpdateResultDto clone(UUID sourceId, TestSuiteCloneRequestDto dto, Jwt jwt) {
        log.info("Cloning TestSuite {} to new suite with name '{}'", sourceId, dto.getName());

        // Step 1: Fetch source suite or 404
        TestSuite source = testSuiteRepository
                .findById(sourceId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + sourceId));

        // Step 1a: Validate optional datasetId override exists
        if (dto.getDatasetId() != null && !datasetRepository.existsById(dto.getDatasetId())) {
            throw new EntityNotFoundException("Dataset not found with id: " + dto.getDatasetId());
        }

        // Step 2: Pre-generate new suite ID
        UUID newId = UUID.randomUUID();
        String createdBy = authorResolver.getCreatedBy(jwt);

        // Step 3: Build new suite entity with overrides + suite-level file ref rewriting
        TestSuite newSuiteEntity = testSuiteMapper.toCloneEntity(source, dto, newId, createdBy);

        boolean cloneSucceeded = false;
        try {
            // Step 4: Copy suite-level DIAL files (before DB transaction)
            fileService.copyFilesBetweenSuites(sourceId, newSuiteEntity.getId());

            // Step 4a: Synchronous suite-level validation against the resolved dataset's schema
            applySuiteValidation(newSuiteEntity);

            // Step 5: DB writes (suite + TSMDs only — test cases are owned by the dataset and shared)
            long cloneTimestamp = clock.millis();
            executeDbWrites(newSuiteEntity, sourceId, newSuiteEntity.getId(), cloneTimestamp);

            // Step 6: Return result with revalidationTask=null (no async task spawned by clone)
            cloneSucceeded = true;
            return TestSuiteUpdateResultDto.builder()
                    .suite(testSuiteMapper.toDto(newSuiteEntity))
                    .revalidationTask(null)
                    .build();
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex, "A test suite with name '" + dto.getName() + "' already exists", dto.getName());
            throw ex;
        } finally {
            if (!cloneSucceeded) {
                fileService.deleteAllBySuiteId(newSuiteEntity.getId());
            }
        }
    }

    /**
     * Applies synchronous suite-level validation against the resolved dataset's schema.
     */
    private void applySuiteValidation(TestSuite entity) {
        TestSuiteRequestDto dto = testSuiteMapper.toRequestDto(entity);

        // Normalize nulls to empty lists (mirrors normalizeRequest() in TestSuiteService)
        if (dto.getInputBindings() == null) {
            dto.setInputBindings(List.of());
        }
        if (dto.getResponseColumns() == null) {
            dto.setResponseColumns(List.of());
        }
        dto.setEndpointRef(endpointSchemaRefResolver.resolve(dto.getEndpointRef()));

        List<FieldDefinitionDto> datasetSchema = datasetSchemaProvider.getSchema(entity.getDatasetId());
        ValidationResult result = suiteValidationService.validateSuite(dto, null, datasetSchema);

        entity.setValid(result.isValid());
        entity.setValidationWarnings(warningsSerializer.serializeWarnings(result.getWarnings()));
    }

    /**
     * Executes suite + TSMD writes in a single transaction. The test-case copy loop is gone —
     * test cases are owned by the dataset and are shared between source and clone (no rows copied,
     * no file-ref rewriting in test-case data). TSMD bindings still carry suite-scoped file refs
     * which are rewritten from source-suite paths to new-suite paths.
     */
    private void executeDbWrites(TestSuite newSuiteEntity, UUID sourceId, UUID newId, long cloneTimestamp) {
        String sourcePrefix = "@ef/suites/" + sourceId + "/";
        String targetPrefix = "@ef/suites/" + newId + "/";
        int batchSize = revalidationProperties.getBatchSize();

        boolean timestampBound = false;
        if (!TransactionSynchronizationManager.hasResource(TransactionTimestampContext.TRANSACTION_TIMESTAMP_KEY)) {
            TransactionSynchronizationManager.bindResource(
                    TransactionTimestampContext.TRANSACTION_TIMESTAMP_KEY, cloneTimestamp);
            timestampBound = true;
        }
        try {
            transactionTemplate.execute(status -> {
                // 5a: Insert new suite
                testSuiteRepository.createWithId(newSuiteEntity, cloneTimestamp);

                // 5b: Paginated TSMD copy
                int offset = 0;
                while (true) {
                    List<TestSuiteMetricDefinition> sourceBatch =
                            testSuiteMetricDefinitionRepository.findBatchByTestSuiteId(sourceId, offset, batchSize);
                    if (sourceBatch.isEmpty()) {
                        break;
                    }
                    List<TestSuiteMetricDefinition> clonedTsmds = new ArrayList<>(sourceBatch.size());
                    for (TestSuiteMetricDefinition tsmd : sourceBatch) {
                        TestSuiteMetricDefinition cloned = TestSuiteMetricDefinition.builder()
                                .id(UUID.randomUUID())
                                .testSuiteId(newId)
                                .metricDeclarationId(tsmd.getMetricDeclarationId())
                                .metricDeclarationVersionId(tsmd.getMetricDeclarationVersionId())
                                .name(tsmd.getName())
                                .configBindings(rewriteRef(tsmd.getConfigBindings(), sourcePrefix, targetPrefix))
                                .inputBindings(rewriteRef(tsmd.getInputBindings(), sourcePrefix, targetPrefix))
                                .enabled(tsmd.isEnabled())
                                .valid(false)
                                .validationWarnings("[]")
                                .build();
                        clonedTsmds.add(cloned);
                    }
                    testSuiteMetricDefinitionRepository.batchInsert(clonedTsmds, cloneTimestamp);
                    offset += sourceBatch.size();
                }

                return null;
            });
        } finally {
            if (timestampBound) {
                TransactionSynchronizationManager.unbindResourceIfPossible(
                        TransactionTimestampContext.TRANSACTION_TIMESTAMP_KEY);
            }
        }
    }

    private static String rewriteRef(String value, String sourcePrefix, String targetPrefix) {
        if (value == null) {
            return null;
        }
        return value.replace(sourcePrefix, targetPrefix);
    }
}
