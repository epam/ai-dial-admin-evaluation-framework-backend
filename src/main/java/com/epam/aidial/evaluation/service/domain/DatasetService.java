package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.exception.OptimisticLockException;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.DatasetCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetDependentSuiteDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetPublishRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityErrorCode;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.DatasetMapper;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository datasetRepository;
    private final DatasetCascadeService datasetCascadeService;
    private final DatasetCloneService datasetCloneService;
    private final TestSuiteService testSuiteService;
    private final TestCaseService testCaseService;
    private final DatasetMapper datasetMapper;
    private final JsonbMapper jsonbMapper;
    private final AuthorResolver authorResolver;
    private final RevalidationService revalidationService;
    private final SchemaValidationService schemaValidationService;
    private final FileService fileService;

    @Qualifier("metaTransactionManager")
    private final PlatformTransactionManager metaTransactionManager;

    private final SortParser sortParser;
    private final FilterParser filterParser;
    private final ObjectMapper objectMapper;
    private final TransactionTimestampContext transactionTimestampContext;

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<DatasetResponseDto> getAll(
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
    public PageResponseDto<DatasetResponseDto> getAll(
            PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        log.debug(
                "Fetching all Datasets with pagination: page={}, size={}",
                pageRequest.getPage(),
                pageRequest.getSize());
        List<FilterCondition> safeFilters = filters != null ? filters : List.of();
        try {
            Page<Dataset> datasetPage = datasetRepository.findAll(pageRequest, safeFilters, includeTotalCount);
            return PageResponseDto.from(datasetPage, datasetMapper::toDto, includeTotalCount);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public DatasetResponseDto getById(UUID id) {
        log.debug("Fetching Dataset by id: {}", id);
        return datasetRepository
                .findById(id)
                .map(datasetMapper::toDto)
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + id));
    }

    /**
     * Lists the test suites that depend on (are bound to) the given dataset, returning a lightweight
     * {@code {id, name, description}} summary per suite. Throws {@link EntityNotFoundException} (HTTP
     * 404) when the dataset does not exist. Cross-domain read delegated to {@link TestSuiteService}.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public List<DatasetDependentSuiteDto> getDependentSuites(UUID id) {
        log.debug("Listing dependent suites for Dataset id: {}", id);
        getById(id);
        return testSuiteService.getDependentSuiteSummaries(id);
    }

    @Transactional("metaTransactionManager")
    public DatasetResponseDto create(DatasetRequestDto requestDto, Jwt jwt) {
        log.info("Creating new Dataset with name: {}", requestDto.getName());
        validateVisibilityBinding(requestDto);
        String createdBy = authorResolver.getCreatedBy(jwt);
        DatasetRequestDto normalized = normalizeRequest(requestDto);
        Dataset dataset = datasetMapper.toEntity(normalized, createdBy);

        try {
            Dataset saved = datasetRepository.save(dataset);
            // Atomic create-and-bind for PRIVATE datasets: the suite update runs in the same
            // transaction as the insert. The DB trigger fires on the suite UPDATE and trivially
            // passes because this new PRIVATE dataset has no other binding yet.
            if (saved.getVisibility() == DatasetVisibility.PRIVATE && requestDto.getBindToSuiteId() != null) {
                testSuiteService.bindDataset(requestDto.getBindToSuiteId(), saved.getId());
            }
            return datasetMapper.toDto(saved);
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex, "A dataset with name '" + dataset.getName() + "' already exists", dataset.getName());
            throw ex;
        }
    }

    /**
     * Deep-copies the dataset {@code id} (row + all test cases with fresh ids and
     * {@code @ef/datasets/{id}/} file-ref rewrites) into a new unbound PUBLIC dataset, starting at
     * {@code version} 0. {@code name} and {@code description} are taken from the request when present,
     * otherwise derived/copied from the source.
     *
     * <p>Only PUBLIC datasets may be cloned here: the clone is unbound, and a PRIVATE dataset must be
     * bound to exactly one suite. Cloning a PRIVATE source therefore fails fast with
     * {@code PRIVATE_DATASET_REQUIRES_SUITE_BINDING} (HTTP 400) before any side effect — clone the
     * owning suite instead, which clones its PRIVATE dataset alongside it.
     *
     * <p>DIAL files are copied before the DB transaction (non-transactional I/O). On transaction
     * failure the just-copied files are deleted best-effort. A duplicate name surfaces as a 409.
     */
    public DatasetResponseDto clone(UUID id, DatasetCloneRequestDto dto, Jwt jwt) {
        log.info("Cloning Dataset {}", id);
        final Dataset source = datasetRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + id));
        if (source.getVisibility() == DatasetVisibility.PRIVATE) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.PRIVATE_DATASET_REQUIRES_SUITE_BINDING,
                    "PRIVATE dataset " + id + " cannot be cloned standalone (the clone would be unbound); "
                            + "clone the owning suite instead, which clones its PRIVATE dataset");
        }
        final UUID newDatasetId = UUID.randomUUID();

        // Pre-TX: DIAL file I/O is not transactional and must not run inside the meta tx.
        datasetCloneService.copyDatasetFiles(id, newDatasetId);

        boolean txSucceeded = false;
        try {
            final TransactionTemplate txTemplate = new TransactionTemplate(metaTransactionManager);
            try {
                txTemplate.execute(status -> {
                    performCloneTransaction(source, newDatasetId, dto, jwt);
                    return null;
                });
            } catch (DataIntegrityViolationException ex) {
                UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                        ex, "A dataset with name '" + dto.getName() + "' already exists", dto.getName());
                throw ex;
            }
            txSucceeded = true;
        } finally {
            if (!txSucceeded) {
                fileService.deleteAllByDatasetId(newDatasetId);
            }
        }

        return datasetMapper.toDto(datasetRepository
                .findById(newDatasetId)
                .orElseThrow(() -> new EntityNotFoundException("Dataset disappeared during clone: " + newDatasetId)));
    }

    private void performCloneTransaction(Dataset source, UUID newDatasetId, DatasetCloneRequestDto dto, Jwt jwt) {
        transactionTimestampContext.initializeIfAbsent();
        final long timestamp = transactionTimestampContext.getTimestamp();
        final String createdBy = authorResolver.getCreatedBy(jwt);
        final String name =
                dto.getName() != null ? dto.getName() : datasetCloneService.deriveCloneName(source.getName());
        final String description = dto.getDescription() != null ? dto.getDescription() : source.getDescription();
        datasetCloneService.cloneRowAndTestCases(
                source, newDatasetId, name, description, createdBy, timestamp, source.getVisibility());
    }

    private void validateVisibilityBinding(DatasetRequestDto requestDto) {
        DatasetVisibility visibility = requestDto.getVisibility();
        if (visibility == null) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.VALIDATION_ERROR,
                    "Dataset visibility is required on create (PUBLIC or PRIVATE)");
        }
        UUID bindToSuiteId = requestDto.getBindToSuiteId();
        if (visibility == DatasetVisibility.PRIVATE && bindToSuiteId == null) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.PRIVATE_DATASET_REQUIRES_SUITE_BINDING,
                    "PRIVATE datasets require bindToSuiteId on create");
        }
        if (visibility == DatasetVisibility.PUBLIC && bindToSuiteId != null) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.PUBLIC_DATASET_FORBIDS_SUITE_BINDING,
                    "PUBLIC datasets do not accept bindToSuiteId on create");
        }
    }

    @Transactional("metaTransactionManager")
    public DatasetUpdateResultDto update(UUID id, DatasetRequestDto requestDto, Long expectedVersion) {
        log.info("Updating Dataset with id: {}", id);

        Dataset existing = datasetRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + id));

        if (expectedVersion != null && !expectedVersion.equals(existing.getVersion())) {
            throw new VersionConflictException(
                    "Dataset version conflict: expected " + expectedVersion + " but current is "
                            + existing.getVersion(),
                    id,
                    expectedVersion);
        }

        DatasetRequestDto normalized = normalizeRequest(requestDto);

        // Compute removed schema fields BEFORE the mapper mutates `existing` so we can
        // prune orphan keys from every test case in the dataset after the dataset itself is saved.
        List<String> removedFields = computeRemovedFields(existing, normalized);
        boolean schemaChanged = isSchemaChanged(existing, normalized);

        datasetMapper.update(existing, normalized);
        if (normalized.getCreatedBy() != null) {
            existing.setCreatedBy(normalized.getCreatedBy());
        }

        try {
            Dataset updated = datasetRepository.save(existing);
            if (!removedFields.isEmpty()) {
                testCaseService.removeDataFields(id, removedFields);
            }
            DatasetResponseDto datasetDto = datasetMapper.toDto(updated);
            RevalidationTaskDto revalidationTask = null;
            if (schemaChanged) {
                schemaValidationService.invalidateSchemaCache(id);
                revalidationTask = revalidationService.startDatasetRevalidation(id);
            }
            return DatasetUpdateResultDto.builder()
                    .dataset(datasetDto)
                    .revalidationTask(revalidationTask)
                    .build();
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex, "A dataset with name '" + existing.getName() + "' already exists", existing.getName());
            throw ex;
        } catch (OptimisticLockException ex) {
            throw new VersionConflictException(ex.getMessage(), id, expectedVersion);
        }
    }

    public void delete(UUID id, boolean force) {
        log.info("Deleting Dataset with id: {} (force={})", id, force);

        final var txTemplate = new TransactionTemplate(metaTransactionManager);
        txTemplate.execute(_ -> {
            transactionTimestampContext.initializeIfAbsent();

            final Dataset dataset = datasetRepository
                    .findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + id));

            if (force || dataset.getVisibility() == DatasetVisibility.PRIVATE) {
                testSuiteService.unbindAllFromDataset(id);
                datasetCascadeService.deleteById(id);
                return null;
            }

            final List<TestSuiteResponseDto> referencingSuites = testSuiteService.getReferencingDataset(id);
            if (!referencingSuites.isEmpty()) {
                throw datasetInUseException(id, referencingSuites);
            }

            try {
                datasetCascadeService.deleteById(id);
            } catch (DataIntegrityViolationException ex) {
                // Race: a suite was inserted between the pre-check and the delete. Re-resolve
                // the dependent set and translate to the same 409.
                List<TestSuiteResponseDto> raceSuites = testSuiteService.getReferencingDataset(id);
                if (!raceSuites.isEmpty()) {
                    throw datasetInUseException(id, raceSuites);
                }
                throw ex;
            }
            return null;
        });
        schemaValidationService.invalidateSchemaCache(id);

        fileService.deleteAllByDatasetId(id);
    }

    /**
     * Transitions a dataset's visibility between PUBLIC and PRIVATE.
     *
     * <p>Acquires a row-level lock on the datasets row (via {@code findByIdForUpdate}) so the
     * binding-count read and the visibility write serialize against concurrent suite-binding
     * writes (which take the same lock via the {@code tg_test_suites_private_binding_guard}
     * trigger). PUBLIC→PRIVATE is allowed only when the dataset has exactly one bound suite;
     * PRIVATE→PUBLIC is always allowed. A no-op transition (target equals current) returns
     * the unchanged dataset without bumping {@code version}.
     */
    @Transactional("metaTransactionManager")
    public DatasetResponseDto transitionVisibility(UUID id, DatasetVisibility target) {
        log.info("Transitioning Dataset {} visibility to {}", id, target);
        Dataset dataset = datasetRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + id));

        if (dataset.getVisibility() == target) {
            return datasetMapper.toDto(dataset);
        }

        if (target == DatasetVisibility.PRIVATE) {
            long boundCount = testSuiteService.countReferencingDataset(id);
            if (boundCount != 1L) {
                throw new DatasetVisibilityRuleException(
                        DatasetVisibilityErrorCode.PRIVATE_TRANSITION_INVALID_BINDING_COUNT,
                        "PUBLIC→PRIVATE transition requires exactly 1 bound suite (current: " + boundCount + ")");
            }
        }
        // PRIVATE→PUBLIC: always allowed regardless of binding count.

        long now = transactionTimestampContext.getTimestamp();
        datasetRepository.updateVisibility(id, target, now);
        Dataset refreshed = datasetRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Dataset disappeared during transition: " + id));
        return datasetMapper.toDto(refreshed);
    }

    /**
     * Publishes a dataset by transitioning it to {@code PUBLIC} and optionally updating its
     * {@code name} and {@code description} in a single atomic write.
     *
     * <p>Acquires a row-level lock (via {@code findByIdForUpdate}) before reading and writing.
     * When no field changes (visibility already {@code PUBLIC}, name and description unchanged)
     * the call is a no-op and returns the current dataset without bumping {@code version}.
     */
    @Transactional("metaTransactionManager")
    public DatasetResponseDto publish(UUID id, DatasetPublishRequestDto dto) {
        log.info("Publishing Dataset {}", id);
        Dataset dataset = datasetRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + id));

        var effectiveName = StringUtils.isNotBlank(dto.getName()) ? dto.getName() : dataset.getName();
        var effectiveDesc = dto.getDescription() != null ? dto.getDescription() : dataset.getDescription();
        boolean alreadyPublic = dataset.getVisibility() == DatasetVisibility.PUBLIC;
        boolean nameUnchanged = effectiveName.equals(dataset.getName());
        boolean descUnchanged = Objects.equals(effectiveDesc, dataset.getDescription());

        if (alreadyPublic && nameUnchanged && descUnchanged) {
            return datasetMapper.toDto(dataset);
        }

        long now = transactionTimestampContext.getTimestamp();
        try {
            datasetRepository.updateVisibilityAndMetadata(
                    id, DatasetVisibility.PUBLIC, effectiveName, effectiveDesc, now);
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex, "A dataset with name '" + effectiveName + "' already exists", effectiveName);
            throw ex;
        }
        return datasetMapper.toDto(datasetRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Dataset disappeared during publish: " + id)));
    }

    private InvalidOperationException datasetInUseException(
            UUID datasetId, List<TestSuiteResponseDto> referencingSuites) {
        String suiteNames =
                referencingSuites.stream().map(TestSuiteResponseDto::getName).collect(Collectors.joining(", "));
        return new InvalidOperationException("Dataset " + datasetId + " cannot be deleted because it is referenced by "
                + referencingSuites.size() + " test suite(s): " + suiteNames);
    }

    private DatasetRequestDto normalizeRequest(DatasetRequestDto dto) {
        if (dto.getTestCaseSchema() == null) {
            dto.setTestCaseSchema(List.of());
        }
        return dto;
    }

    /**
     * Returns the field names that exist in the current dataset schema but are absent from the new schema.
     * MUST be called before mapper.update() mutates the existing entity.
     */
    private List<String> computeRemovedFields(Dataset existing, DatasetRequestDto normalized) {
        List<FieldDefinitionDto> oldSchema = jsonbMapper.mapFieldDefinitions(existing.getTestCaseSchema());
        List<FieldDefinitionDto> newSchema = normalized.getTestCaseSchema();
        if (oldSchema == null || oldSchema.isEmpty()) {
            return List.of();
        }
        Set<String> newNames = new HashSet<>();
        if (newSchema != null) {
            for (FieldDefinitionDto f : newSchema) {
                if (f.getName() != null) {
                    newNames.add(f.getName());
                }
            }
        }
        List<String> removed = new ArrayList<>();
        for (FieldDefinitionDto f : oldSchema) {
            if (f.getName() != null && !newNames.contains(f.getName())) {
                removed.add(f.getName());
            }
        }
        return removed;
    }

    /**
     * Detects whether the dataset's serialized {@code testCaseSchema} JSON differs from the request.
     * Compares semantically via Jackson so key-order differences don't count as a change.
     */
    private boolean isSchemaChanged(Dataset existing, DatasetRequestDto normalized) {
        String newSchemaJson = jsonbMapper.mapFieldDefinitions(normalized.getTestCaseSchema());
        return !jsonEquals(existing.getTestCaseSchema(), newSchemaJson);
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
