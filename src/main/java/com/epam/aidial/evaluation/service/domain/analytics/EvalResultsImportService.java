package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns both halves of eval-results import that touch data (as opposed to the suite/concurrency
 * guards on {@code TestSuiteRunService}): pre-persistence batch validation ({@link #validateBatch})
 * and result persistence ({@link #persistResults}).
 *
 * <p>{@code validateBatch}/{@code testCaseIdentity} carry no {@code @Transactional} annotation of
 * their own — they simply run inside whichever ambient transaction the caller
 * ({@code TestSuiteRunService.importResultsAndEvaluate},
 * {@code @Transactional("metaTransactionManager")}) already has open. This is why colocating them
 * here does not revive the mixed-datasource-transactional-method problem
 * {@code docs/patterns/dual-datasource.md} warns about: only {@link #persistResults}, annotated
 * {@code @Transactional("analyticsTransactionManager")}, actually opens a transaction on this
 * class.
 *
 * <p>Schema validation and per-row field constraints are handled upstream by
 * {@link EvalResultsCsvParser#parse}, which produces {@link TestCaseRunResult} stubs
 * already validated, with {@code extractedColumns} and {@code extractionWarnings} trusted
 * verbatim from the CSV (caller-supplied, same as {@code testCaseData}).
 * {@code validateBatch} performs only structural batch-level checks
 * (empty/size/duplicate/timing) that need all items together.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class EvalResultsImportService {

    private final TestCaseRunResultRepository resultRepository;
    private final AnalyticsResultsProperties analyticsResultsProperties;
    /**
     * Provides the {@code csv.import.batch-size} value reused for chunked {@link #persistResults}
     * writes — mirrors {@link com.epam.aidial.evaluation.service.domain.CsvImportService}'s own
     * chunked-insert loop, which uses the same config to bound each {@code JDBC} batch.
     */
    private final CsvImportProperties csvImportProperties;

    /**
     * Validates the eval-results import batch: non-empty, within the configured max batch size, no
     * duplicate {@code (testCaseId-or-testCaseName, runIndex)} pair, and
     * {@code completedAt >= startedAt} per item. Identity ({@code testCaseId}/{@code testCaseName})
     * is used only for this in-batch duplicate check — it is never resolved against any dataset
     * (see {@code design.md} Decision 4).
     *
     * <p>Per-row field constraints and dataset-schema validation are handled upstream by
     * {@link EvalResultsCsvParser#parse}, which guarantees {@code testCaseData} is always a
     * serialized JSON object string.
     */
    public void validateBatch(List<TestCaseRunResult> results) {
        if (results.isEmpty()) {
            throw new ValidationException("results must not be empty");
        }

        int maxItems = analyticsResultsProperties.getBatch().getMaxItems();
        if (results.size() > maxItems) {
            throw new ValidationException("Batch size " + results.size() + " exceeds maximum of " + maxItems);
        }

        Set<String> seenKeys = new HashSet<>();
        for (TestCaseRunResult item : results) {
            String identity = testCaseIdentity(item);
            String key = identity + "#" + item.getRunIndex();
            if (!seenKeys.add(key)) {
                throw new ValidationException(
                        "Duplicate result for test case '" + identity + "' and runIndex " + item.getRunIndex());
            }
            if (item.getExecCompletedAtMs() != null
                    && item.getExecStartedAtMs() != null
                    && item.getExecCompletedAtMs() < item.getExecStartedAtMs()) {
                throw new ValidationException("completedAt must be >= startedAt for test case '" + identity + "'");
            }
        }
    }

    private String testCaseIdentity(TestCaseRunResult item) {
        if (item.getTestCaseId() != null) {
            return item.getTestCaseId().toString();
        }
        if (item.getTestCaseName() != null && !item.getTestCaseName().isBlank()) {
            return item.getTestCaseName();
        }
        throw new ValidationException("Either testCaseId or testCaseName is required for each result");
    }

    /**
     * Fills in run-context fields and persists the items in chunks. The stubs produced by
     * {@link EvalResultsCsvParser#parse} have {@code id}/{@code testSuiteRunId}/
     * {@code testSuiteId}/{@code createdAtMs} left as {@code null}/{@code 0}; this method assigns
     * them from the created run. {@code extractedColumns} and {@code extractionWarnings} are
     * already set by the parser from the caller-supplied CSV columns and are persisted verbatim.
     *
     * <p>Writes are chunked via {@code csv.import.batch-size} (reusing the same property that
     * {@link com.epam.aidial.evaluation.service.domain.CsvImportService} uses) to keep any single
     * JDBC batch bounded, per the project's bulk-write convention.
     */
    @Transactional("analyticsTransactionManager")
    public void persistResults(UUID testSuiteId, TestSuiteRun run, List<TestCaseRunResult> items) {
        int batchSize = csvImportProperties.getBatchSize();
        int total = items.size();
        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(start + batchSize, total);
            List<TestCaseRunResult> chunk = items.subList(start, end).stream()
                    .map(item -> item.toBuilder()
                            .id(UUID.randomUUID())
                            .testSuiteId(testSuiteId)
                            .testSuiteRunId(run.getId())
                            .createdAtMs(run.getCreatedAt())
                            .build())
                    .toList();
            resultRepository.saveAll(chunk);
        }
        log.info("Imported {} eval result(s) for run {}", total, run.getId());
    }
}
