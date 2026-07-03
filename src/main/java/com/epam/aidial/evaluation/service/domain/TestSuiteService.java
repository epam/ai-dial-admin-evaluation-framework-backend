package com.epam.aidial.evaluation.service.domain;

import static com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext.TRANSACTION_TIMESTAMP_KEY;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.exception.OptimisticLockException;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.dto.DatasetDependentSuiteDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetDetachRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteDeleteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityErrorCode;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.job.RunnableTestCaseSelector;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteMapper;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class TestSuiteService {

    private final TestSuiteRepository testSuiteRepository;
    private final DatasetQueryService datasetQueryService;
    private final DatasetCascadeService datasetCascadeService;
    private final DatasetCloneService datasetCloneService;
    private final TestSuiteMapper testSuiteMapper;
    private final JsonbMapper jsonbMapper;
    private final AuthorResolver authorResolver;
    private final EndpointSchemaRefResolver endpointSchemaRefResolver;
    private final SuiteValidationService suiteValidationService;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final RunnableTestCaseSelector runnableTestCaseSelector;
    private final TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;
    private final FileService fileService;
    private final Clock clock;

    @Qualifier("metaTransactionManager")
    private final PlatformTransactionManager metaTransactionManager;

    private final SortParser sortParser;
    private final FilterParser filterParser;
    private final ValidationWarningsSerializer warningsSerializer;
    private final ObjectMapper objectMapper;
    private final TestSuiteRequestValidator testSuiteRequestValidator;

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<TestSuiteResponseDto> getAll(
            int page, int size, List<String> sort, List<String> filter, boolean includeTotalCount) {
        PageRequest pageRequest = PageRequest.builder()
                .page(page)
                .size(size)
                .sort(sortParser.parse(sort != null ? sort : List.of()))
                .build();
        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        return getAll(pageRequest, filters, includeTotalCount);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<TestSuiteResponseDto> getAll(PageRequest pageRequest, boolean includeTotalCount) {
        return getAll(pageRequest, List.of(), includeTotalCount);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<TestSuiteResponseDto> getAll(
            PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        log.debug(
                "Fetching all TestSuites with pagination: page={}, size={}",
                pageRequest.getPage(),
                pageRequest.getSize());
        List<FilterCondition> safeFilters = filters != null ? filters : List.of();
        try {
            Page<TestSuite> testSuitePage = testSuiteRepository.findAll(pageRequest, safeFilters, includeTotalCount);
            return PageResponseDto.from(testSuitePage, testSuiteMapper::toDto, includeTotalCount);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public TestSuiteResponseDto getById(UUID id) {
        log.debug("Fetching TestSuite by id: {}", id);
        return testSuiteRepository
                .findById(id)
                .map(testSuiteMapper::toDto)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + id));
    }

    @Transactional("metaTransactionManager")
    public TestSuiteResponseDto create(TestSuiteRequestDto testSuiteRequestDto, Jwt jwt) {
        log.info("Creating new TestSuite with name: {}", testSuiteRequestDto.getName());
        testSuiteRequestValidator.validateSuiteTypeFields(testSuiteRequestDto);
        testSuiteRequestValidator.validateTestSuiteSchemas(testSuiteRequestDto);
        testSuiteRequestValidator.validateTemplateLimits(testSuiteRequestDto);
        // Suites may be created in the unbound state (datasetId == null); the schema is empty
        // and the suite cannot run until a datasetId is set. The DB trigger handles PRIVATE
        // uniqueness when a non-null datasetId is supplied.
        List<FieldDefinitionDto> datasetSchema = testSuiteRequestDto.getDatasetId() != null
                ? datasetSchemaProvider.getSchema(testSuiteRequestDto.getDatasetId())
                : List.of();
        String createdBy = authorResolver.getCreatedBy(jwt);
        TestSuiteRequestDto normalized = normalizeRequest(testSuiteRequestDto);
        validateTestCaseFilter(testSuiteRequestDto.getDatasetId(), normalized.getTestCaseFilter());
        TestSuite testSuite = testSuiteMapper.toEntity(normalized, createdBy);

        ValidationResult suiteValidation = suiteValidationService.validateSuite(normalized, null, datasetSchema);
        testSuite.setValid(suiteValidation.isValid());
        testSuite.setValidationWarnings(warningsSerializer.serializeWarnings(suiteValidation.getWarnings()));

        try {
            TestSuite saved = testSuiteRepository.save(testSuite);
            return testSuiteMapper.toDto(saved);
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex, "A test suite with name '" + testSuite.getName() + "' already exists", testSuite.getName());
            throw ex;
        }
    }

    @Transactional("metaTransactionManager")
    public TestSuiteResponseDto update(UUID id, TestSuiteRequestDto testSuiteRequestDto, Long expectedVersion) {
        log.info("Updating TestSuite with id: {}", id);

        TestSuite existing = testSuiteRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + id));

        if (expectedVersion != null && !expectedVersion.equals(existing.getVersion())) {
            throw new VersionConflictException(
                    "TestSuite version conflict: expected " + expectedVersion + " but current is "
                            + existing.getVersion(),
                    id,
                    expectedVersion);
        }

        validateSuiteTypeImmutability(existing, testSuiteRequestDto);
        validatePrivateRebindNotForbidden(existing.getDatasetId(), testSuiteRequestDto.getDatasetId());
        testSuiteRequestValidator.validateSuiteTypeFields(testSuiteRequestDto);
        testSuiteRequestValidator.validateTestSuiteSchemas(testSuiteRequestDto);
        testSuiteRequestValidator.validateTemplateLimits(testSuiteRequestDto);
        // Resolves through DatasetSchemaProvider which throws EntityNotFoundException on miss.
        // When the suite is unbound (or being unbound) the schema is empty — no provider call.
        List<FieldDefinitionDto> datasetSchema = testSuiteRequestDto.getDatasetId() != null
                ? datasetSchemaProvider.getSchema(testSuiteRequestDto.getDatasetId())
                : List.of();
        TestSuiteRequestDto normalized = normalizeRequest(testSuiteRequestDto);
        validateTestCaseFilter(testSuiteRequestDto.getDatasetId(), normalized.getTestCaseFilter());

        boolean tsmdResponseColumnsChanged = isResponseColumnsChanged(existing, normalized);
        testSuiteMapper.update(existing, normalized);
        if (normalized.getCreatedBy() != null) {
            existing.setCreatedBy(normalized.getCreatedBy());
        }

        ValidationResult suiteValidation = suiteValidationService.validateSuite(normalized, id, datasetSchema);
        existing.setValid(suiteValidation.isValid());
        existing.setValidationWarnings(warningsSerializer.serializeWarnings(suiteValidation.getWarnings()));

        try {
            TestSuite updated = testSuiteRepository.save(existing);
            // TSMD bindings reference both the dataset's testCaseSchema and the suite's responseColumns.
            // testCaseSchema-side changes are revalidated by DatasetService (Phase 2 of the dataset revalidation task).
            // responseColumns are suite-owned, so suite update is responsible for kicking off TSMD revalidation when
            // they change.
            if (tsmdResponseColumnsChanged) {
                String datasetSchemaJson = jsonbMapper.mapFieldDefinitions(datasetSchema);
                testSuiteMetricDefinitionService.revalidateAllForSuite(
                        id, datasetSchemaJson, updated.getResponseColumns());
            }
            return testSuiteMapper.toDto(updated);
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex, "A test suite with name '" + existing.getName() + "' already exists", existing.getName());
            throw ex;
        } catch (OptimisticLockException ex) {
            throw new VersionConflictException(ex.getMessage(), id, expectedVersion);
        }
    }

    public TestSuiteDeleteResponseDto delete(UUID id) {
        log.info("Deleting TestSuite with id: {}", id);

        UUID cascadedPrivateDatasetId;
        TransactionTemplate txTemplate = new TransactionTemplate(metaTransactionManager);
        cascadedPrivateDatasetId = txTemplate.execute(status -> {
            TestSuite suite = testSuiteRepository
                    .findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + id));
            // Capture the bound dataset's visibility BEFORE deleting the suite, so we can
            // cascade-delete a PRIVATE dataset in the same transaction. For PUBLIC-bound or
            // unbound suites the dataset is preserved (existing behavior).
            UUID boundDatasetId = suite.getDatasetId();
            DatasetVisibility boundVisibility = null;
            if (boundDatasetId != null) {
                boundVisibility =
                        datasetQueryService.getVisibility(boundDatasetId).orElse(null);
            }
            testSuiteRepository.deleteById(id);
            if (boundVisibility == DatasetVisibility.PRIVATE) {
                // Test cases cascade via the dataset FK. The suite's runs and their snapshots
                // were already cascade-removed via the V1.6 test_suite_runs FK ON DELETE CASCADE.
                testSuiteRepository.unbindAllByDatasetId(boundDatasetId);
                datasetCascadeService.deleteById(boundDatasetId);
                return boundDatasetId;
            }
            return null;
        });

        // After transaction commits, best-effort DIAL file cleanup (keyed by suiteId; unchanged).
        fileService.deleteAllBySuiteId(id);
        if (cascadedPrivateDatasetId != null) {
            fileService.deleteAllByDatasetId(cascadedPrivateDatasetId);
        }

        return TestSuiteDeleteResponseDto.builder().deleted(true).build();
    }

    /**
     * Sets the suite's {@code datasetId} to the supplied value, guarded by the PRIVATE-rebind rule.
     * Used by {@code DatasetService.create} to atomically bind a freshly-created PRIVATE dataset
     * to its target suite within the same transaction. The DB trigger
     * {@code tg_test_suites_private_binding_guard} still handles the
     * "new PRIVATE dataset already bound elsewhere" case via {@link DataIntegrityViolationException}.
     */
    @Transactional("metaTransactionManager")
    public TestSuiteResponseDto bindDataset(UUID suiteId, UUID datasetId) {
        TestSuite suite = testSuiteRepository
                .findById(suiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + suiteId));
        validatePrivateRebindNotForbidden(suite.getDatasetId(), datasetId);
        suite.setDatasetId(datasetId);
        return testSuiteMapper.toDto(testSuiteRepository.save(suite));
    }

    /**
     * Returns every suite whose {@code dataset_id} equals the supplied id.
     * Used by {@code DatasetService.delete} for the 409 pre-check on PUBLIC datasets.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public List<TestSuiteResponseDto> getReferencingDataset(UUID datasetId) {
        return testSuiteRepository.findSuitesReferencingDataset(datasetId).stream()
                .map(testSuiteMapper::toDto)
                .toList();
    }

    /**
     * Returns a lightweight {@code {id, name, description}} summary for every suite bound to the
     * given dataset. Backed by a selective-column projection so the suite's large JSONB columns are
     * not fetched. Used by the dataset → dependent-suites listing endpoint.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public List<DatasetDependentSuiteDto> getDependentSuiteSummaries(UUID datasetId) {
        return testSuiteRepository.findSuiteSummariesReferencingDataset(datasetId).stream()
                .map(summary -> DatasetDependentSuiteDto.builder()
                        .id(summary.id())
                        .name(summary.name())
                        .description(summary.description())
                        .build())
                .toList();
    }

    /**
     * Detaches every suite currently bound to the given dataset by setting
     * {@code test_suites.dataset_id = NULL}. Returns the number of suites unbound.
     * Used by {@code DatasetService.delete} on the PRIVATE-dataset path so {@code DatasetService}
     * does not need to reach into {@link TestSuiteRepository} directly.
     */
    @Transactional("metaTransactionManager")
    public int unbindAllFromDataset(UUID datasetId) {
        return testSuiteRepository.unbindAllByDatasetId(datasetId);
    }

    /**
     * Counts suites currently bound to the given dataset. Used by
     * {@code DatasetService.transitionVisibility} to validate the PUBLIC→PRIVATE precondition
     * (exactly 1 bound suite) under the dataset row lock; the read participates in the
     * caller's transaction via REQUIRED propagation, so the lock semantics are preserved.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public long countReferencingDataset(UUID datasetId) {
        return testSuiteRepository.countByDatasetId(datasetId);
    }

    /**
     * Forks the suite's bound PUBLIC dataset into a new PRIVATE clone and rebinds the suite to it.
     * Pre-TX: copies DIAL files for the new dataset folder.
     * In-TX: clones the dataset row + test cases, remaps {@code disabledTestCaseIds}, rebinds suite.
     * On failure: best-effort cleanup of any copied files.
     */
    public TestSuiteResponseDto detachDataset(UUID suiteId, DatasetDetachRequestDto dto, Jwt jwt) {
        log.info("Detaching dataset from suite {}", suiteId);
        TestSuite suite = testSuiteRepository
                .findById(suiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + suiteId));

        UUID sourceDatasetId = suite.getDatasetId();
        if (sourceDatasetId == null) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.SUITE_HAS_NO_DATASET,
                    "Suite " + suiteId + " has no bound dataset to detach from");
        }

        Dataset source = datasetQueryService
                .findById(sourceDatasetId)
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + sourceDatasetId));

        if (source.getVisibility() != DatasetVisibility.PUBLIC) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.PRIVATE_DATASET_REBIND_FORBIDDEN,
                    "Suite " + suiteId + " is already bound to a PRIVATE dataset — no detach needed");
        }

        final UUID newDatasetId = UUID.randomUUID();
        final long timestamp = clock.millis();

        datasetCloneService.copyDatasetFiles(sourceDatasetId, newDatasetId);
        boolean txSucceeded = false;
        try {
            TransactionTemplate txTemplate = new TransactionTemplate(metaTransactionManager);
            boolean timestampBound = false;
            if (!TransactionSynchronizationManager.hasResource(TRANSACTION_TIMESTAMP_KEY)) {
                TransactionSynchronizationManager.bindResource(TRANSACTION_TIMESTAMP_KEY, timestamp);
                timestampBound = true;
            }
            try {
                txTemplate.execute(status -> {
                    performDetachTransaction(source, newDatasetId, dto, suiteId, suite, timestamp, jwt);
                    return null;
                });
            } catch (DataIntegrityViolationException ex) {
                UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                        ex, "A dataset with name '" + dto.getName() + "' already exists", dto.getName());
                throw ex;
            } finally {
                if (timestampBound) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_TIMESTAMP_KEY);
                }
            }
            txSucceeded = true;
        } finally {
            if (!txSucceeded) {
                fileService.deleteAllByDatasetId(newDatasetId);
            }
        }

        return testSuiteMapper.toDto(suite);
    }

    private void performDetachTransaction(
            Dataset source,
            UUID newDatasetId,
            DatasetDetachRequestDto dto,
            UUID suiteId,
            TestSuite suite,
            long timestamp,
            Jwt jwt) {
        final String resolvedName =
                dto.getName() != null ? dto.getName() : datasetCloneService.deriveCloneName(source.getName());
        final String createdBy = authorResolver.getCreatedBy(jwt);
        Map<UUID, UUID> tcIdMap = datasetCloneService.cloneRowAndTestCases(
                source,
                newDatasetId,
                resolvedName,
                source.getDescription(),
                createdBy,
                timestamp,
                DatasetVisibility.PRIVATE);
        String remappedDisabledIds = testSuiteMapper.remapDisabledIds(suite.getDisabledTestCaseIds(), tcIdMap);
        testSuiteRepository.updateDatasetId(suiteId, newDatasetId, remappedDisabledIds, timestamp);
        suite.setDatasetId(newDatasetId);
        suite.setDisabledTestCaseIds(remappedDisabledIds);
        // Mirror the version/timestamp bump applied by updateDatasetId so the returned
        // DTO reflects the persisted row (the in-memory copy is what we map back).
        suite.setVersion(suite.getVersion() == null ? 1L : suite.getVersion() + 1L);
        suite.setUpdatedAt(timestamp);
    }

    /**
     * Rejects rebinding when the suite is currently bound to a PRIVATE dataset and the requested
     * {@code datasetId} differs (including {@code null}). The user must explicitly
     * {@code DELETE /datasets/{id}} the PRIVATE dataset first; rebinding silently would imply
     * destructive data loss as a side effect of an update.
     */
    private void validatePrivateRebindNotForbidden(UUID currentDatasetId, UUID requestedDatasetId) {
        if (currentDatasetId == null || Objects.equals(currentDatasetId, requestedDatasetId)) {
            return;
        }
        DatasetVisibility currentVisibility =
                datasetQueryService.getVisibility(currentDatasetId).orElse(null);
        if (currentVisibility == DatasetVisibility.PRIVATE) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.PRIVATE_DATASET_REBIND_FORBIDDEN,
                    "Cannot change datasetId of a suite bound to a PRIVATE dataset. "
                            + "DELETE the PRIVATE dataset first (which unbinds the suite), then PATCH the suite.");
        }
    }

    /**
     * Rejects update if suiteType is changed.
     */
    private void validateSuiteTypeImmutability(TestSuite existing, TestSuiteRequestDto dto) {
        SuiteType existingType = existing.getSuiteType() != null ? existing.getSuiteType() : SuiteType.DEPLOYMENT;
        SuiteType requestType = dto.getSuiteType() != null ? dto.getSuiteType() : SuiteType.DEPLOYMENT;
        if (existingType != requestType) {
            throw new ValidationException("Suite type cannot be changed. Current: " + existingType.getValue()
                    + ", requested: " + requestType.getValue());
        }
    }

    /**
     * Validates a suite's {@code testCaseFilter} at write time against the bound dataset's test-case
     * schema. A null filter is a no-op. A non-null filter on an unbound suite ({@code datasetId == null})
     * is rejected because it can be neither validated nor applied. Otherwise the filter is translated
     * against the dataset's typed bindings via {@link RunnableTestCaseSelector#validateFilter}, which
     * throws {@link ValidationException} (→ HTTP 400) on an unknown field, type error, or malformed filter.
     */
    private void validateTestCaseFilter(UUID datasetId, Map<String, Object> testCaseFilter) {
        if (testCaseFilter == null) {
            return;
        }
        if (datasetId == null) {
            throw new ValidationException(
                    "testCaseFilter requires the suite to be bound to a dataset (datasetId must be set)");
        }
        runnableTestCaseSelector.validateFilter(datasetId, jsonbMapper.mapTestCaseFilter(testCaseFilter));
    }

    private TestSuiteRequestDto normalizeRequest(TestSuiteRequestDto dto) {
        if (dto.getInputBindings() == null) {
            dto.setInputBindings(List.of());
        }
        if (dto.getResponseColumns() == null) {
            dto.setResponseColumns(List.of());
        } else {
            for (ResponseColumnDefinitionDto col : dto.getResponseColumns()) {
                if (col != null && col.getType() == null) {
                    col.setType(SchemaFieldType.STRING);
                }
            }
        }
        dto.setEndpointRef(endpointSchemaRefResolver.resolve(dto.getEndpointRef()));
        return dto;
    }

    /**
     * Detects whether the suite's responseColumns changed. TSMD bindings reference both the
     * dataset-owned testCaseSchema and the suite-owned responseColumns; when only responseColumns
     * change here at the suite level, the TSMD revalidation must be kicked off explicitly because
     * the dataset-side revalidation flow only fires on testCaseSchema diffs.
     * Compared semantically (ignores key order). Must be called BEFORE mapper.update() mutates existing.
     */
    private boolean isResponseColumnsChanged(TestSuite existing, TestSuiteRequestDto normalized) {
        TestSuite temp = new TestSuite();
        temp.setResponseColumns(existing.getResponseColumns());
        testSuiteMapper.update(temp, normalized);
        return !jsonEquals(existing.getResponseColumns(), temp.getResponseColumns());
    }

    private boolean jsonEquals(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null || b.isBlank();
        }
        if (b == null || b.isBlank()) {
            return false;
        }
        try {
            JsonNode nodeA = objectMapper.readTree(a);
            JsonNode nodeB = objectMapper.readTree(b);
            return nodeA.equals(nodeB);
        } catch (JacksonException e) {
            return false;
        }
    }
}
