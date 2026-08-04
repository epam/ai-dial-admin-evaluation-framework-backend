package com.epam.aidial.evaluation.service.domain;

import static com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext.TRANSACTION_TIMESTAMP_KEY;

import com.epam.aidial.evaluation.configuration.properties.validation.RevalidationProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityErrorCode;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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
    private final TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;
    private final DatasetRepository datasetRepository;
    private final DatasetCloneService datasetCloneService;
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
            TestSuiteMetricDefinitionService testSuiteMetricDefinitionService,
            DatasetRepository datasetRepository,
            DatasetCloneService datasetCloneService,
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
        this.testSuiteMetricDefinitionService = testSuiteMetricDefinitionService;
        this.datasetRepository = datasetRepository;
        this.datasetCloneService = datasetCloneService;
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
        final TestSuite sourceSuite = testSuiteRepository
                .findById(sourceId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + sourceId));

        final UUID sourceDatasetId = sourceSuite.getDatasetId();
        final Dataset sourceDataset = sourceDatasetId != null
                ? datasetRepository.findById(sourceDatasetId).orElse(null)
                : null;

        final boolean clonePrivateDataset =
                sourceDataset != null && sourceDataset.getVisibility() == DatasetVisibility.PRIVATE;

        final UUID datasetIdOverride = dto.getDatasetId();

        if (clonePrivateDataset && datasetIdOverride != null && !datasetIdOverride.equals(sourceDatasetId)) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.PRIVATE_DATASET_REBIND_FORBIDDEN,
                    "Cannot rebind a clone to a different dataset when the source suite is bound to a PRIVATE "
                            + "dataset. Omit datasetId (the PRIVATE dataset is cloned automatically) or pass the "
                            + "source suite's dataset id.");
        }

        if (datasetIdOverride != null && !datasetRepository.existsById(datasetIdOverride)) {
            throw new EntityNotFoundException("Dataset for override not found by id: " + datasetIdOverride);
        }

        final UUID newSuiteId = UUID.randomUUID();
        final String createdBy = authorResolver.getCreatedBy(jwt);

        final UUID newDatasetId = clonePrivateDataset ? UUID.randomUUID() : null;

        // Step 3: Build new suite entity with overrides + suite-level file ref rewriting
        final TestSuite newSuiteEntity = testSuiteMapper.toCloneEntity(sourceSuite, dto, newSuiteId, createdBy);
        if (clonePrivateDataset) {
            newSuiteEntity.setDatasetId(newDatasetId);
        }

        boolean cloneSucceeded = false;
        try {
            fileService.copyFilesBetweenSuites(sourceId, newSuiteEntity.getId());

            if (clonePrivateDataset) {
                datasetCloneService.copyDatasetFiles(sourceDataset.getId(), newDatasetId);
            }

            /*
             in case of private dataset - validate against existing source dataset, otherwise take id of
             existing/re-assigned public dataset
            */
            final UUID datasetToValidateAgainst =
                    clonePrivateDataset ? sourceDataset.getId() : newSuiteEntity.getDatasetId();
            applySuiteValidation(newSuiteEntity, datasetToValidateAgainst);

            final var tsmdRevalidation =
                    decideOnTsmdRevalidation(dto, datasetIdOverride, sourceDatasetId, datasetToValidateAgainst);

            final long cloneTimestamp = clock.millis();
            executeDbWrites(
                    newSuiteEntity,
                    sourceId,
                    newSuiteEntity.getId(),
                    cloneTimestamp,
                    clonePrivateDataset ? sourceDataset : null,
                    newDatasetId,
                    createdBy,
                    tsmdRevalidation);

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
                // DB rows roll back with the transaction; only the non-transactional DIAL files copied
                // before the transaction need explicit best-effort cleanup.
                fileService.deleteAllBySuiteId(newSuiteEntity.getId());
                if (clonePrivateDataset) {
                    fileService.deleteAllByDatasetId(newDatasetId);
                }
            }
        }
    }

    private @NonNull TsmdRevalidationDecision decideOnTsmdRevalidation(
            TestSuiteCloneRequestDto dto, UUID datasetIdOverride, UUID sourceDatasetId, UUID datasetToValidateAgainst) {
        // TSMD revalidation is required only when an override can change a TSMD validation input:
        // a differing datasetId (possibly different testCaseSchema) or a responseColumns override.
        // Otherwise, every validation input matches the source and the source verdict is copied verbatim.
        final boolean tsmdRevalidationRequired =
                (datasetIdOverride != null && !datasetIdOverride.equals(sourceDatasetId))
                        || dto.getResponseColumns() != null;

        // Raw testCaseSchema JSON for the resolved dataset; needed only on the recompute path
        // (revalidateAllForSuite consumes the raw String). The source dataset's schema is the
        // clone's schema for a private auto-clone (copied verbatim).
        final String tsmdRevalidationSchemaJson = tsmdRevalidationRequired && datasetToValidateAgainst != null
                ? datasetRepository
                        .findById(datasetToValidateAgainst)
                        .map(Dataset::getTestCaseSchema)
                        .orElse(null)
                : null;
        return new TsmdRevalidationDecision(tsmdRevalidationRequired, tsmdRevalidationSchemaJson);
    }

    /**
     * Applies synchronous suite-level validation against the dataset schema identified by
     * {@code schemaDatasetId}. When auto-cloning a PRIVATE dataset the cloned row does not exist yet,
     * so the caller passes the source dataset id (the clone's schema is identical); otherwise this is
     * the resolved/override dataset id bound to the suite.
     */
    private void applySuiteValidation(TestSuite entity, UUID schemaDatasetId) {
        TestSuiteRequestDto dto = testSuiteMapper.toRequestDto(entity);

        // Normalize nulls to empty lists (mirrors normalizeRequest() in TestSuiteService)
        if (dto.getInputBindings() == null) {
            dto.setInputBindings(List.of());
        }
        if (dto.getResponseColumns() == null) {
            dto.setResponseColumns(List.of());
        }
        dto.setEndpointRef(endpointSchemaRefResolver.resolve(dto.getEndpointRef()));

        List<FieldDefinitionDto> datasetSchema =
                schemaDatasetId != null ? datasetSchemaProvider.getSchema(schemaDatasetId) : List.of();
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
    private void executeDbWrites(
            TestSuite newSuiteEntity,
            UUID sourceId,
            UUID newId,
            long cloneTimestamp,
            Dataset datasetToClone,
            UUID newDatasetId,
            String createdBy,
            TsmdRevalidationDecision tsmdRevalidation) {
        String sourcePrefix = "@ef/suites/" + sourceId + "/";
        String targetPrefix = "@ef/suites/" + newId + "/";
        int batchSize = revalidationProperties.getBatchSize();

        boolean timestampBound = false;
        if (!TransactionSynchronizationManager.hasResource(TRANSACTION_TIMESTAMP_KEY)) {
            TransactionSynchronizationManager.bindResource(TRANSACTION_TIMESTAMP_KEY, cloneTimestamp);
            timestampBound = true;
        }
        try {
            transactionTemplate.execute(status -> {
                // 5pre: When auto-cloning, clone the dataset + its test cases first (joins this tx),
                // then remap the inherited disabledTestCaseIds onto the new test-case ids.
                if (datasetToClone != null) {
                    Map<UUID, UUID> testCaseIdMap = datasetCloneService.cloneRowAndTestCases(
                            datasetToClone,
                            newDatasetId,
                            datasetCloneService.deriveCloneName(datasetToClone.getName()),
                            datasetToClone.getDescription(),
                            createdBy,
                            cloneTimestamp,
                            DatasetVisibility.PRIVATE);
                    newSuiteEntity.setDisabledTestCaseIds(
                            testSuiteMapper.remapDisabledIds(newSuiteEntity.getDisabledTestCaseIds(), testCaseIdMap));
                }

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
                                .valid(!tsmdRevalidation.required() && tsmd.isValid())
                                .validationWarnings(tsmdRevalidation.required() ? "[]" : tsmd.getValidationWarnings())
                                .build();
                        clonedTsmds.add(cloned);
                    }
                    testSuiteMetricDefinitionRepository.batchInsert(clonedTsmds, cloneTimestamp);
                    offset += sourceBatch.size();
                }

                if (tsmdRevalidation.required()) {
                    testSuiteMetricDefinitionService.revalidateAllForSuite(
                            newId, tsmdRevalidation.schema(), newSuiteEntity.getResponseColumns());
                }

                return null;
            });
        } finally {
            if (timestampBound) {
                TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_TIMESTAMP_KEY);
            }
        }
    }

    private static String rewriteRef(String value, String sourcePrefix, String targetPrefix) {
        if (value == null) {
            return null;
        }
        return value.replace(sourcePrefix, targetPrefix);
    }

    private record TsmdRevalidationDecision(boolean required, String schema) {}
}
