package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBatchPutItemDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkDeleteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkDeleteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkOperationDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchResponseDto.BulkResultDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchResponseDto.ItemResultDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseItemOperationDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.TestCaseMapper;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class TestCaseService {

    private static final Set<String> VALIDATION_RELEVANT_FIELDS = Set.of("data", "testCaseName");
    private static final String MULTI_TURN_TURN_CONSTRAINT = "uq_test_cases_multi_turn_turn";

    private final TestCaseRepository testCaseRepository;
    private final DatasetQueryService datasetQueryService;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseValidationService testCaseValidationService;
    private final FilterParser filterParser;
    private final SortParser sortParser;
    private final ValidationWarningsSerializer warningsSerializer;
    private final TestCaseProperties testCaseProperties;
    private final TestCaseBulkPatchValidator bulkPatchValidator;
    private final TestCaseBulkDeleteValidator bulkDeleteValidator;
    private final TestCaseBulkSelectorResolver bulkSelectorResolver;
    private final TransactionTimestampContext transactionTimestampContext;
    private final MultiTurnFieldsValidator multiTurnFieldsValidator;

    @Transactional("metaTransactionManager")
    public TestCaseResponseDto create(UUID datasetId, TestCaseRequestDto dto, boolean includeWarnings) {
        List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(datasetId);
        TestCase entity = testCaseMapper.toEntity(dto, datasetId);
        multiTurnFieldsValidator.validate(entity.getMultiTurnId(), entity.getTurnIndex());
        runValidation(entity, schema);
        try {
            TestCase saved = testCaseRepository.save(entity);
            return testCaseMapper.toDto(saved, includeWarnings);
        } catch (DataIntegrityViolationException ex) {
            rethrowWriteConflict(ex, entity.getTestCaseName());
            throw ex;
        }
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public TestCaseResponseDto getById(UUID datasetId, UUID id, boolean includeWarnings) {
        TestCase entity = testCaseRepository
                .findByIdAndDatasetId(id, datasetId)
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + id));
        return testCaseMapper.toDto(entity, includeWarnings);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<TestCaseResponseDto> getAll(
            UUID datasetId,
            int page,
            int size,
            List<String> sort,
            List<String> filter,
            boolean includeTotalCount,
            boolean includeWarnings) {
        ensureDatasetExists(datasetId);
        PageRequest pageRequest = PageRequest.builder()
                .page(page)
                .size(size)
                .sort(sortParser.parse(sort != null ? sort : List.of()))
                .build();
        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        Page<TestCase> domainPage =
                testCaseRepository.findAllByDatasetId(datasetId, pageRequest, filters, includeTotalCount);
        return PageResponseDto.from(domainPage, tc -> testCaseMapper.toDto(tc, includeWarnings), includeTotalCount);
    }

    @Transactional("metaTransactionManager")
    public TestCaseResponseDto update(UUID datasetId, UUID id, TestCaseRequestDto dto, boolean includeWarnings) {
        TestCase existing = testCaseRepository
                .findByIdAndDatasetId(id, datasetId)
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + id));
        List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(datasetId);
        testCaseMapper.updateEntity(existing, dto);
        multiTurnFieldsValidator.validate(existing.getMultiTurnId(), existing.getTurnIndex());
        runValidation(existing, schema);
        try {
            TestCase updated = testCaseRepository.update(existing);
            if (updated == null) {
                throw new EntityNotFoundException("TestCase not found: " + id);
            }
            return testCaseMapper.toDto(updated, includeWarnings);
        } catch (DataIntegrityViolationException ex) {
            rethrowWriteConflict(ex, existing.getTestCaseName());
            throw ex;
        }
    }

    @Transactional("metaTransactionManager")
    public TestCaseResponseDto patch(UUID datasetId, UUID id, Map<String, Object> patchBody, boolean includeWarnings) {
        TestCase existing = testCaseRepository
                .findByIdAndDatasetId(id, datasetId)
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + id));
        List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(datasetId);
        applyMergePatch(existing, patchBody);
        multiTurnFieldsValidator.validate(existing.getMultiTurnId(), existing.getTurnIndex());
        runValidation(existing, schema);
        try {
            TestCase updated = testCaseRepository.update(existing);
            if (updated == null) {
                throw new EntityNotFoundException("TestCase not found: " + id);
            }
            return testCaseMapper.toDto(updated, includeWarnings);
        } catch (DataIntegrityViolationException ex) {
            rethrowWriteConflict(ex, existing.getTestCaseName());
            throw ex;
        }
    }

    @Transactional("metaTransactionManager")
    public List<TestCaseResponseDto> batchUpdate(
            UUID datasetId, List<TestCaseBatchPutItemDto> items, boolean includeWarnings) {
        validateBatchRequest(items.stream().map(TestCaseBatchPutItemDto::getId).toList());
        ensureDatasetExists(datasetId);
        List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(datasetId);

        List<UUID> ids = items.stream().map(TestCaseBatchPutItemDto::getId).toList();
        Map<UUID, TestCase> existingById = fetchAndVerifyAllExist(ids, datasetId);

        List<TestCase> entities = new ArrayList<>(items.size());
        for (TestCaseBatchPutItemDto item : items) {
            TestCase entity = existingById.get(item.getId());
            testCaseMapper.updateEntity(entity, item);
            multiTurnFieldsValidator.validate(entity.getMultiTurnId(), entity.getTurnIndex());
            entities.add(entity);
        }

        validateBatchNameUniqueness(entities, datasetId);
        entities.forEach(e -> runValidation(e, schema));
        persistBatch(entities);

        return entities.stream()
                .map(e -> testCaseMapper.toDto(e, includeWarnings))
                .toList();
    }

    @Transactional("metaTransactionManager")
    public List<TestCaseResponseDto> batchPatch(
            UUID datasetId, List<Map<String, Object>> items, boolean includeWarnings) {
        List<UUID> ids = extractAndValidatePatchIds(items);
        validateBatchRequest(ids);
        ensureDatasetExists(datasetId);
        List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(datasetId);

        Map<UUID, TestCase> existingById = fetchAndVerifyAllExist(ids, datasetId);

        List<TestCase> entities = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            UUID id = extractUuid(item.get("id"));
            TestCase entity = existingById.get(id);
            Map<String, Object> patchBody = new HashMap<>(item);
            patchBody.remove("id");
            applyMergePatch(entity, patchBody);
            multiTurnFieldsValidator.validate(entity.getMultiTurnId(), entity.getTurnIndex());
            entities.add(entity);
        }

        validateBatchNameUniqueness(entities, datasetId);
        entities.forEach(e -> runValidation(e, schema));
        persistBatch(entities);

        return entities.stream()
                .map(e -> testCaseMapper.toDto(e, includeWarnings))
                .toList();
    }

    @Transactional("metaTransactionManager")
    public TestCaseBulkPatchResponseDto bulkPatch(
            UUID datasetId, TestCaseBulkPatchRequestDto request, boolean includeWarnings) {
        bulkPatchValidator.validate(request);
        ensureDatasetExists(datasetId);
        List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(datasetId);
        long updatedAt = transactionTimestampContext.getTimestamp();

        List<TestCaseBulkOperationDto> bulkOps =
                request.getBulkOperations() != null ? request.getBulkOperations() : List.of();
        List<TestCaseItemOperationDto> itemOps =
                request.getItemOperations() != null ? request.getItemOperations() : List.of();

        List<BulkResultDto> bulkResults = new ArrayList<>(bulkOps.size());
        for (int i = 0; i < bulkOps.size(); i++) {
            TestCaseBulkOperationDto op = bulkOps.get(i);
            List<UUID> ids = bulkSelectorResolver.resolve(datasetId, op.getSelector());
            int updated;
            if (ids.isEmpty()) {
                updated = 0;
            } else {
                updated = testCaseRepository.updateFieldsByIds(
                        datasetId, ids, serializeBulkPatchValues(op.getPatch()), updatedAt);
            }
            bulkResults.add(BulkResultDto.builder()
                    .opIndex(i)
                    .matched(ids.size())
                    .updated(updated)
                    .build());
        }

        // Pass 1: prepare every item in memory (fetch, merge-patch, revalidate) without writing, so a
        // name permutation within the request can be parked before any row is persisted. All rows are
        // fetched in a single query (as batch PUT/PATCH does) rather than one SELECT per item.
        final Map<UUID, TestCase> existingById = fetchAndVerifyAllExist(
                itemOps.stream().map(TestCaseItemOperationDto::getId).toList(), datasetId);

        final List<ItemOperation> operationsToPerform = new ArrayList<>(itemOps.size());
        for (TestCaseItemOperationDto op : itemOps) {
            TestCase existing = existingById.get(op.getId());
            String beforeName = existing.getTestCaseName();
            TestCase before = copyTestCase(existing);

            applyMergePatch(existing, op.getPatch());
            if (touchesValidationRelevantField(op.getPatch())) {
                runValidation(existing, schema);
            }

            boolean changed = !equalForUpdate(before, existing);
            boolean renamed = !Objects.equals(beforeName, existing.getTestCaseName());
            operationsToPerform.add(new ItemOperation(existing, changed, renamed));
        }

        // Validate final-state name uniqueness before any write (consistent with batch PUT/PATCH),
        // so genuine duplicates are rejected up front. The per-item DataIntegrityViolationException
        // catch below remains as a DB-level backstop.
        final List<TestCase> renamedEntities = operationsToPerform.stream()
                .filter(ItemOperation::renamed)
                .map(ItemOperation::entity)
                .toList();
        if (!renamedEntities.isEmpty()) {
            validateBatchNameUniqueness(renamedEntities, datasetId);
        }

        // Pass 2: park renamed rows at temporary names so a swap/cycle does not trip the
        // per-statement unique index during the apply pass.
        if (!renamedEntities.isEmpty()) {
            testCaseRepository.parkTestCaseNames(renamedEntities);
        }

        // Pass 3: apply final values.
        final List<ItemResultDto> itemResults = new ArrayList<>(itemOps.size());
        for (ItemOperation item : operationsToPerform) {
            TestCase entity = item.entity();
            try {
                TestCase persisted = testCaseRepository.update(entity);
                if (persisted == null) {
                    throw new EntityNotFoundException("TestCase not found: " + entity.getId());
                }
            } catch (DataIntegrityViolationException ex) {
                UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                        ex,
                        "A test case with name '" + entity.getTestCaseName() + "' already exists in this dataset",
                        entity.getTestCaseName());
                throw ex;
            }
            itemResults.add(ItemResultDto.builder()
                    .id(entity.getId())
                    .updated(item.changed())
                    .build());
        }

        return TestCaseBulkPatchResponseDto.builder()
                .bulkResults(bulkResults)
                .itemResults(itemResults)
                .build();
    }

    private static boolean touchesValidationRelevantField(Map<String, Object> patch) {
        if (patch == null) {
            return false;
        }
        for (String key : patch.keySet()) {
            if (VALIDATION_RELEVANT_FIELDS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** A composite-bulk itemOperation prepared in memory (pass 1) and awaiting park/apply. */
    private record ItemOperation(TestCase entity, boolean changed, boolean renamed) {}

    private static TestCase copyTestCase(TestCase original) {
        return TestCase.builder()
                .id(original.getId())
                .datasetId(original.getDatasetId())
                .testCaseName(original.getTestCaseName())
                .data(original.getData())
                .multiTurnId(original.getMultiTurnId())
                .turnIndex(original.getTurnIndex())
                .valid(original.isValid())
                .validationWarnings(original.getValidationWarnings())
                .createdAt(original.getCreatedAt())
                .updatedAt(original.getUpdatedAt())
                .build();
    }

    private static boolean equalForUpdate(TestCase a, TestCase b) {
        return Objects.equals(a.getTestCaseName(), b.getTestCaseName())
                && Objects.equals(a.getData(), b.getData())
                && Objects.equals(a.getMultiTurnId(), b.getMultiTurnId())
                && Objects.equals(a.getTurnIndex(), b.getTurnIndex())
                && a.isValid() == b.isValid()
                && Objects.equals(a.getValidationWarnings(), b.getValidationWarnings());
    }

    private void rethrowWriteConflict(DataIntegrityViolationException ex, String testCaseName) {
        if (UniqueConstraintViolationDetector.mentionsConstraint(ex, MULTI_TURN_TURN_CONSTRAINT)) {
            throw new UniqueConstraintViolationException(
                    "A test case already exists for this multi-turn and turn index", (String) null);
        }
        UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                ex, "A test case with name '" + testCaseName + "' already exists in this dataset", testCaseName);
    }

    private void validateBatchRequest(List<UUID> ids) {
        if (ids.isEmpty()) {
            throw new ValidationException("Batch request must not be empty");
        }
        int maxItems = testCaseProperties.getBatch().getMaxItems();
        if (ids.size() > maxItems) {
            throw new ValidationException("Batch size " + ids.size() + " exceeds maximum allowed " + maxItems);
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID id : ids) {
            if (!seen.add(id)) {
                throw new ValidationException("Duplicate test case id in batch: " + id);
            }
        }
    }

    private List<UUID> extractAndValidatePatchIds(List<Map<String, Object>> items) {
        List<UUID> ids = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            Object rawId = item.get("id");
            if (rawId == null) {
                throw new ValidationException("Item at index " + i + " is missing required 'id' field");
            }
            try {
                ids.add(extractUuid(rawId));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Item at index " + i + " has invalid UUID format for 'id': " + rawId);
            }
        }
        return ids;
    }

    private static UUID extractUuid(Object value) {
        if (value instanceof UUID) {
            return (UUID) value;
        }
        return UUID.fromString(value.toString());
    }

    private Map<UUID, TestCase> fetchAndVerifyAllExist(List<UUID> ids, UUID datasetId) {
        List<TestCase> found = testCaseRepository.findAllByIdsAndDatasetId(ids, datasetId);
        Map<UUID, TestCase> byId = found.stream().collect(Collectors.toMap(TestCase::getId, Function.identity()));
        for (UUID id : ids) {
            if (!byId.containsKey(id)) {
                throw new EntityNotFoundException("TestCase not found: " + id);
            }
        }
        return byId;
    }

    private void validateBatchNameUniqueness(List<TestCase> entities, UUID datasetId) {
        Map<String, String> lowerToOriginal = new LinkedHashMap<>();
        for (TestCase entity : entities) {
            String lower = entity.getTestCaseName().toLowerCase();
            String prev = lowerToOriginal.put(lower, entity.getTestCaseName());
            if (prev != null) {
                throw new UniqueConstraintViolationException(
                        "Duplicate test case name within batch: '" + entity.getTestCaseName() + "'",
                        entity.getTestCaseName());
            }
        }

        Set<UUID> batchIds = entities.stream().map(TestCase::getId).collect(Collectors.toSet());
        List<String> colliding = testCaseRepository.findCollidingNamesByDatasetIdExcludingIds(
                datasetId, batchIds, lowerToOriginal.keySet());
        if (!colliding.isEmpty()) {
            throw new UniqueConstraintViolationException(
                    "Test case name already exists in this dataset: '" + colliding.get(0) + "'", colliding);
        }
    }

    private void persistBatch(List<TestCase> entities) {
        try {
            testCaseRepository.batchUpdate(entities);
        } catch (DataIntegrityViolationException ex) {
            if (UniqueConstraintViolationDetector.mentionsConstraint(ex, MULTI_TURN_TURN_CONSTRAINT)) {
                throw new UniqueConstraintViolationException(
                        "A test case already exists for this multi-turn and turn index", (String) null);
            }
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex, "A test case name collision was detected during batch update");
            throw ex;
        }
    }

    /**
     * Runs schema-shape validation of the test-case data against the dataset's testCaseSchema.
     * Template-variable and FILE-reference validation are not performed here — they live at the
     * suite level and are handled by suite/run-time validation (or Phase 2 of dataset revalidation).
     */
    private void runValidation(TestCase entity, List<FieldDefinitionDto> schema) {
        Map<String, Object> dataMap = warningsSerializer.deserializeMap(entity.getData());
        ValidationResult result = testCaseValidationService.validateTestCase(
                dataMap, schema, null, List.of(), false, entity.getDatasetId());
        entity.setValid(result.isValid());
        entity.setValidationWarnings(
                warningsSerializer.serializeWarnings(result.getWarnings() != null ? result.getWarnings() : List.of()));
    }

    @Transactional("metaTransactionManager")
    public void delete(UUID datasetId, UUID id) {
        boolean deleted = testCaseRepository.deleteByIdAndDatasetId(id, datasetId);
        if (!deleted) {
            throw new EntityNotFoundException("TestCase not found: " + id);
        }
    }

    /**
     * Removes the given field keys from the {@code data} JSONB column of every test case in the dataset.
     * Invoked by {@code DatasetService.update} after a schema field is dropped, to prune orphan keys.
     */
    @Transactional("metaTransactionManager")
    public void removeDataFields(UUID datasetId, Collection<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return;
        }
        testCaseRepository.removeDataFields(datasetId, fieldNames);
    }

    @Transactional("metaTransactionManager")
    public long deleteAll(UUID datasetId, List<String> filter) {
        ensureDatasetExists(datasetId);
        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        return testCaseRepository.deleteAllByDatasetId(datasetId, filters);
    }

    @Transactional("metaTransactionManager")
    public TestCaseBulkDeleteResponseDto bulkDelete(UUID datasetId, TestCaseBulkDeleteRequestDto request) {
        ensureDatasetExists(datasetId);
        bulkDeleteValidator.validate(request);
        List<UUID> deletedIds = testCaseRepository.deleteByIdsAndDatasetId(datasetId, request.getIds());
        Set<UUID> deletedSet = new HashSet<>(deletedIds);
        List<UUID> deleted =
                request.getIds().stream().filter(deletedSet::contains).toList();
        List<UUID> notFound =
                request.getIds().stream().filter(id -> !deletedSet.contains(id)).toList();
        return TestCaseBulkDeleteResponseDto.builder()
                .deleted(deleted)
                .notFound(notFound)
                .build();
    }

    private void ensureDatasetExists(UUID datasetId) {
        if (!datasetQueryService.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> serializeBulkPatchValues(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(patch.size());
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if ("data".equals(entry.getKey())) {
                Object v = entry.getValue();
                if (v instanceof Map) {
                    result.put("data", warningsSerializer.serializeMap((Map<String, Object>) v));
                } else if (v == null) {
                    result.put("data", null);
                } else {
                    throw new ValidationException("bulk-patch 'data' must be a JSON object");
                }
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void applyMergePatch(TestCase entity, Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            return;
        }
        if (patch.containsKey("testCaseName") && patch.get("testCaseName") != null) {
            entity.setTestCaseName(patch.get("testCaseName").toString());
        }
        if (patch.containsKey("data")) {
            Object v = patch.get("data");
            if (v instanceof Map) {
                Map<String, Object> patchData = (Map<String, Object>) v;
                Map<String, Object> merged = mergeMaps(warningsSerializer.deserializeMap(entity.getData()), patchData);
                entity.setData(warningsSerializer.serializeMap(merged));
            }
        }
    }

    private Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> result = new LinkedHashMap<>(base != null ? base : Map.of());
        if (patch == null) {
            return result;
        }
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (e.getValue() == null) {
                result.remove(e.getKey());
            } else if (e.getValue() instanceof Map && result.get(e.getKey()) instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> merged =
                        mergeMaps((Map<String, Object>) result.get(e.getKey()), (Map<String, Object>) e.getValue());
                result.put(e.getKey(), merged);
            } else {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }
}
