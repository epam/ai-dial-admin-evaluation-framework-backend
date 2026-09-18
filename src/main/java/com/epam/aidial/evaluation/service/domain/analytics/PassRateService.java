package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.properties.analytics.PassRateProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassRateStats;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRunRef;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunPassRateDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves a suite's test-case pass-rate breakdown over its most recent runs.
 *
 * <p>Meta drives the run window: {@link TestSuiteRunService#findRecentRuns} returns up to {@code lastN}
 * runs of any status, newest-first, each carrying only {@code id}/{@code status}/{@code createdAtMs}. The
 * ids are then handed to one analytics aggregate ({@link EvalSummaryRepository#countPassRateByLatestComputation})
 * that resolves each run's latest computation and counts its rows. A run the aggregate returns no row for
 * (no eval summaries yet) is dropped; the remaining runs are re-ordered back to the meta order and decorated
 * with {@code status}/{@code runCreatedAtMs}.
 *
 * <p>Not {@code @Transactional}: {@link TestSuiteService#getById} and {@link TestSuiteRunService#findRecentRuns}
 * each open their own read-only meta transaction, and the analytics aggregate runs inside an explicit,
 * read-only {@link TransactionTemplate} built here from the {@code analyticsTransactionManager} — the same
 * shape as {@code RunComparisonService}, so meta and analytics are never open together.
 */
@Slf4j
@Service
@LogExecution
public class PassRateService {

    private final TestSuiteService testSuiteService;
    private final TestSuiteRunService testSuiteRunService;
    private final EvalSummaryRepository evalSummaryRepository;
    private final PassRateProperties properties;
    private final TransactionTemplate analyticsTransactionTemplate;

    public PassRateService(
            TestSuiteService testSuiteService,
            TestSuiteRunService testSuiteRunService,
            EvalSummaryRepository evalSummaryRepository,
            PassRateProperties properties,
            @Qualifier("analyticsTransactionManager") PlatformTransactionManager analyticsTxManager) {
        this.testSuiteService = testSuiteService;
        this.testSuiteRunService = testSuiteRunService;
        this.evalSummaryRepository = evalSummaryRepository;
        this.properties = properties;
        this.analyticsTransactionTemplate = new TransactionTemplate(analyticsTxManager);
        this.analyticsTransactionTemplate.setReadOnly(true);
    }

    /**
     * @param suiteId the suite whose runs are aggregated
     * @param lastN the number of most recent runs to consider, or {@code null} to apply
     *     {@code analytics.pass-rate.default-last-n}
     */
    public SuitePassRateResponseDto getPassRate(UUID suiteId, Integer lastN) {
        final int resolvedLastN = lastN != null ? lastN : properties.getDefaultLastN();
        if (resolvedLastN > properties.getMaxLastN()) {
            throw new ValidationException(
                    "lastN must not exceed " + properties.getMaxLastN() + " (analytics.pass-rate.max-last-n)");
        }

        // Existence check first (404), then the run window - both meta reads, each in their own transaction.
        testSuiteService.getById(suiteId);
        final List<TestSuiteRunRef> recentRuns = testSuiteRunService.findRecentRuns(suiteId, resolvedLastN);

        if (recentRuns.isEmpty()) {
            return SuitePassRateResponseDto.builder()
                    .testSuiteId(suiteId)
                    .runs(List.of())
                    .build();
        }

        final List<UUID> runIds = recentRuns.stream().map(TestSuiteRunRef::id).toList();
        final List<RunPassRateStats> stats = analyticsTransactionTemplate.execute(
                status -> evalSummaryRepository.countPassRateByLatestComputation(runIds));

        final Map<UUID, RunPassRateStats> statsByRunId = new LinkedHashMap<>();
        for (final RunPassRateStats stat : stats) {
            statsByRunId.put(stat.runId(), stat);
        }

        final List<RunPassRateDto> runs = recentRuns.stream()
                .filter(run -> statsByRunId.containsKey(run.id()))
                .map(run -> toDto(run, statsByRunId.get(run.id())))
                .toList();

        return SuitePassRateResponseDto.builder()
                .testSuiteId(suiteId)
                .runs(runs)
                .build();
    }

    private RunPassRateDto toDto(TestSuiteRunRef run, RunPassRateStats stat) {
        return RunPassRateDto.builder()
                .testSuiteRunId(run.id())
                .computationId(stat.computationId())
                .status(run.status())
                .runCreatedAtMs(run.createdAtMs())
                .failedCount(stat.failed())
                .successPassedCount(stat.successPassed())
                .successNotPassedCount(stat.successNotPassed())
                .successNoVerdictCount(stat.successNoVerdict())
                .totalCount(stat.total())
                .build();
    }
}
