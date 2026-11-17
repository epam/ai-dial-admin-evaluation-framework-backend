package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateSummaryDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mean pass rate of a set of suites' latest runs, and of their previous runs.
 *
 * <p>Per suite, the latest two {@code COMPLETED} runs are used, each at its latest computation, with the pass
 * rule of {@link PassRateCalculator}. Runs that are in flight, failed or cancelled are never used: their
 * scores are partial or still being written, so they would skew the rate. The summary is the mean of the
 * per-suite rates (each suite counts once); a suite without a previous run is left out of the previous mean
 * only. A selected run that has no score rows at its latest computation contributes nothing for its
 * position; it is not replaced by an older run.
 *
 * <p>Two datasources, no nesting, three steps: the run selection (status lives in the meta datasource) goes
 * through {@link TestSuiteRunService}; the analytics reads (each run's latest computation, counts) then run in
 * one read-only analytics transaction; finally the run thresholds are read from meta again, after that
 * transaction has completed. A meta query never runs inside the analytics transaction.
 */
@Slf4j
@Service
@LogExecution
public class SuitePassRateService {

    private static final int RUNS_PER_SUITE = 2;
    private static final int LATEST_POSITION = 0;
    private static final int PREVIOUS_POSITION = 1;

    private final EvalSummaryRepository evalSummaryRepository;
    private final TestCaseEvalScoreRepository testCaseEvalScoreRepository;
    private final TestSuiteRunService testSuiteRunService;
    private final PassRateCalculator passRateCalculator;
    private final TransactionTemplate analyticsTransactionTemplate;

    public SuitePassRateService(
            EvalSummaryRepository evalSummaryRepository,
            TestCaseEvalScoreRepository testCaseEvalScoreRepository,
            TestSuiteRunService testSuiteRunService,
            PassRateCalculator passRateCalculator,
            @Qualifier("analyticsTransactionManager") PlatformTransactionManager analyticsTxManager) {
        this.evalSummaryRepository = evalSummaryRepository;
        this.testCaseEvalScoreRepository = testCaseEvalScoreRepository;
        this.testSuiteRunService = testSuiteRunService;
        this.passRateCalculator = passRateCalculator;
        this.analyticsTransactionTemplate = new TransactionTemplate(analyticsTxManager);
        this.analyticsTransactionTemplate.setReadOnly(true);
    }

    /**
     * @throws ValidationException when {@code suiteIds} is null, empty, contains a null entry, or has more than {@link
     *     ValidationConstants#MAX_BATCH_SUITE_IDS} distinct ids
     */
    public SuitePassRateSummaryDto summarize(List<UUID> suiteIds) {
        final List<UUID> distinctIds = validateAndDedupe(suiteIds);

        final Map<UUID, List<UUID>> completedRunsBySuite =
                testSuiteRunService.getLatestCompletedRunIdsBySuite(distinctIds, RUNS_PER_SUITE);
        final List<UUID> runIds =
                completedRunsBySuite.values().stream().flatMap(List::stream).toList();
        if (runIds.isEmpty()) {
            return buildSummary(completedRunsBySuite, Map.of(), Map.of());
        }

        final Map<UUID, RunPassCounts> countsByRun = analyticsTransactionTemplate.execute(status -> readCounts(runIds));
        final Map<UUID, Double> thresholds = countsByRun.isEmpty()
                ? Map.of()
                : testSuiteRunService.getThresholdsByRunIds(countsByRun.keySet());

        return buildSummary(completedRunsBySuite, countsByRun, thresholds);
    }

    private Map<UUID, RunPassCounts> readCounts(List<UUID> runIds) {
        final Map<UUID, UUID> computations = evalSummaryRepository.findLatestComputationIds(runIds);
        return testCaseEvalScoreRepository.countByRunAndComputation(computations);
    }

    private SuitePassRateSummaryDto buildSummary(
            Map<UUID, List<UUID>> completedRunsBySuite,
            Map<UUID, RunPassCounts> countsByRun,
            Map<UUID, Double> thresholds) {
        final List<Double> latestRates = new ArrayList<>();
        final List<Double> previousRates = new ArrayList<>();
        for (final List<UUID> suiteRuns : completedRunsBySuite.values()) {
            addRate(latestRates, suiteRuns, LATEST_POSITION, countsByRun, thresholds);
            addRate(previousRates, suiteRuns, PREVIOUS_POSITION, countsByRun, thresholds);
        }

        return SuitePassRateSummaryDto.builder()
                .passRate(passRateCalculator.mean(latestRates))
                .suiteCount(latestRates.size())
                .previousPassRate(passRateCalculator.mean(previousRates))
                .previousSuiteCount(previousRates.size())
                .build();
    }

    /** Adds the pass rate of the run at {@code position} in the suite's newest-first runs, if there is one. */
    private void addRate(
            List<Double> target,
            List<UUID> suiteRuns,
            int position,
            Map<UUID, RunPassCounts> countsByRun,
            Map<UUID, Double> thresholds) {
        if (position >= suiteRuns.size()) {
            return;
        }
        final UUID runId = suiteRuns.get(position);
        final RunPassCounts counts = countsByRun.get(runId);
        if (counts == null) {
            return;
        }
        final Double rate = passRateCalculator.rate(counts, thresholds.containsKey(runId));
        if (rate != null) {
            target.add(rate);
        }
    }

    private static List<UUID> validateAndDedupe(List<UUID> suiteIds) {
        if (suiteIds == null || suiteIds.isEmpty()) {
            throw new ValidationException("suiteIds must not be empty");
        }
        if (suiteIds.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("suiteIds must not contain null entries");
        }
        final List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(suiteIds));
        if (distinct.size() > ValidationConstants.MAX_BATCH_SUITE_IDS) {
            throw new ValidationException(
                    "suiteIds must not exceed " + ValidationConstants.MAX_BATCH_SUITE_IDS + " distinct entries");
        }
        return distinct;
    }
}
