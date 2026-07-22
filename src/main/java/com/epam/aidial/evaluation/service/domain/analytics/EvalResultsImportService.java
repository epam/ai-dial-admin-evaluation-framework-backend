package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.ResponseColumnExtractor;
import com.epam.aidial.evaluation.service.domain.SchemaValidationService;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalResultsImportItemDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.JacksonMapper;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.TestCaseRunResultMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns both halves of eval-results import that touch data (as opposed to the suite/concurrency
 * guards on {@code TestSuiteRunService}): pre-persistence batch validation ({@link #validateBatch})
 * and result persistence ({@link #persistResults}).
 *
 * <p>{@code validateBatch}/{@code validateTestCaseData}/{@code testCaseIdentity} carry no
 * {@code @Transactional} annotation of their own — they simply run inside whichever ambient
 * transaction the caller ({@code TestSuiteRunService.importResultsAndEvaluate},
 * {@code @Transactional("metaTransactionManager")}) already has open, including for the meta-side
 * {@link DatasetSchemaProvider} lookup they perform. This is why colocating them here does not
 * revive the mixed-datasource-transactional-method problem {@code docs/patterns/dual-datasource.md}
 * warns about: only {@link #persistResults}, annotated {@code @Transactional("analyticsTransactionManager")},
 * actually opens a transaction on this class.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class EvalResultsImportService {

    private final ResponseColumnExtractor responseColumnExtractor;
    private final TestCaseRunResultMapper resultMapper;
    private final TestCaseRunResultRepository resultRepository;
    private final JacksonMapper jacksonMapper;
    private final JsonbMapper jsonbMapper;
    private final AnalyticsResultsProperties analyticsResultsProperties;
    private final SchemaValidationService schemaValidationService;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final ObjectMapper objectMapper;

    /**
     * Validates the eval-results import batch: non-empty, within the configured max batch size, no
     * duplicate {@code (testCaseId-or-testCaseName, runIndex)} pair, {@code testCaseData} is a JSON
     * object, {@code testCaseData} conforms to the dataset schema, and {@code completedAt >= startedAt}
     * per item. Identity ({@code testCaseId}/{@code testCaseName}) is used only for this in-batch
     * duplicate check — it is never resolved against any dataset (see {@code design.md} Decision 4).
     */
    public void validateBatch(UUID datasetId, List<EvalResultsImportItemDto> results) {
        if (results.isEmpty()) {
            throw new ValidationException("results must not be empty");
        }

        int maxItems = analyticsResultsProperties.getBatch().getMaxItems();
        if (results.size() > maxItems) {
            throw new ValidationException("Batch size " + results.size() + " exceeds maximum of " + maxItems);
        }

        Set<String> seenKeys = new HashSet<>();
        for (EvalResultsImportItemDto item : results) {
            String identity = testCaseIdentity(item);
            String key = identity + "#" + item.getRunIndex();
            if (!seenKeys.add(key)) {
                throw new ValidationException(
                        "Duplicate result for test case '" + identity + "' and runIndex " + item.getRunIndex());
            }
            if (!item.getTestCaseData().isObject()) {
                throw new ValidationException("testCaseData must be a JSON object for test case '" + identity + "'");
            }
            if (item.getExecutionInfo().getCompletedAt()
                    < item.getExecutionInfo().getStartedAt()) {
                throw new ValidationException("completedAt must be >= startedAt for test case '" + identity + "'");
            }

            validateTestCaseData(datasetId, identity, item);
        }
    }

    private void validateTestCaseData(UUID datasetId, String identity, EvalResultsImportItemDto item) {
        var schema = datasetSchemaProvider.getSchema(datasetId);
        if (schema.isEmpty()) {
            return; // No schema to validate against
        }

        Map<String, Object> schemaMap = SchemaValidationService.buildFieldSchema(schema);
        Map<String, Object> dataMap;
        try {
            dataMap = objectMapper.convertValue(item.getTestCaseData(), new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(
                    "Failed to parse testCaseData for test case '" + identity + "': " + ex.getMessage());
        }

        var validationResult = schemaValidationService.validate(dataMap, schemaMap);
        if (!validationResult.isValid()) {
            String warnings = validationResult.getWarnings().stream()
                    .map(w -> w.getPath() + ": " + w.getMessage())
                    .limit(5)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown validation error");
            throw new ValidationException(
                    "testCaseData validation failed for test case '" + identity + "': " + warnings);
        }
    }

    private String testCaseIdentity(EvalResultsImportItemDto item) {
        if (item.getTestCaseId() != null) {
            return item.getTestCaseId().toString();
        }
        if (item.getTestCaseName() != null && !item.getTestCaseName().isBlank()) {
            return item.getTestCaseName();
        }
        throw new ValidationException("Either testCaseId or testCaseName is required for each result");
    }

    /**
     * Extracts response columns and persists one {@link TestCaseRunResult} per item.
     * {@code testCaseId}/{@code testCaseName}/{@code testCaseData} are taken straight from each item —
     * caller-trusted, never resolved against any dataset. {@code responseColumnsJson} is the suite's
     * raw {@code response_columns} JSONB column, deserialized once per call.
     */
    @Transactional("analyticsTransactionManager")
    public void persistResults(
            UUID testSuiteId, TestSuiteRun run, List<EvalResultsImportItemDto> items, String responseColumnsJson) {
        List<ResponseColumnDefinitionDto> responseColumns = jsonbMapper.mapResponseColumns(responseColumnsJson);
        List<TestCaseRunResult> entities = items.stream()
                .map(item -> toEntity(testSuiteId, run, responseColumns, item))
                .toList();

        resultRepository.saveAll(entities);
        log.info("Imported {} eval result(s) for run {}", entities.size(), run.getId());
    }

    private TestCaseRunResult toEntity(
            UUID testSuiteId,
            TestSuiteRun run,
            List<ResponseColumnDefinitionDto> responseColumns,
            EvalResultsImportItemDto item) {
        String responseBody = jacksonMapper.asString(item.getResponseBody());
        ResponseColumnExtractor.ExtractionResult extraction =
                responseColumnExtractor.extract(responseColumns, responseBody);
        return resultMapper.toEntity(item, testSuiteId, run.getId(), run.getCreatedAt(), extraction);
    }
}
