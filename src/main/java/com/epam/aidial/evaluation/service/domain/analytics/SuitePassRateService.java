package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import com.epam.aidial.evaluation.data.db.analytics.model.SuiteRunRank;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateSummaryDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mean pass rate of a set of suites' latest runs, and of their previous runs.
 *
 * <p>Per suite, the latest two runs that have {@code test_case_eval_scores} rows are used, each at its
 * latest computation, with the pass rule of {@link PassRateCalculator}. The summary is the mean of the
 * per-suite rates (each suite counts once); a suite without a previous run is left out of the previous
 * mean only.
 *
 * <p>Two datasources, no nesting: the analytics reads (run selection, latest computation, counts) run in one
 * read-only analytics transaction; the run thresholds are then read from the meta datasource through
 * {@link TestSuiteRunService}, after that transaction has completed. A meta query never runs inside the
 * analytics transaction.
 */
@Slf4j
@Service
@LogExecution
public class SuitePassRateService {

    private static final int RUNS_PER_SUITE = 2;

    private final EvalSummaryRepository evalSummaryRepository;
    private final TestCaseEvalScoreRepository testCaseEvalScoreRepository;
    private final ComputationResolver computationResolver;
    private final TestSuiteRunService testSuiteRunService;
    private final PassRateCalculator passRateCalculator;
    private final TransactionTemplate analyticsTransactionTemplate;

    public SuitePassRateService(
            EvalSummaryRepository evalSummaryRepository,
            TestCaseEvalScoreRepository testCaseEvalScoreRepository,
            ComputationResolver computationResolver,
            TestSuiteRunService testSuiteRunService,
            PassRateCalculator passRateCalculator,
            @Qualifier("analyticsTransactionManager") PlatformTransactionManager analyticsTxManager) {
        this.evalSummaryRepository = evalSummaryRepository;
        this.testCaseEvalScoreRepository = testCaseEvalScoreRepository;
        this.computationResolver = computationResolver;
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

        final AnalyticsReads reads = analyticsTransactionTemplate.execute(status -> readAnalytics(distinctIds));
        final Map<UUID, Double> thresholds = reads.countsByRun().isEmpty()
                ? Map.of()
                : testSuiteRunService.getThresholdsByRunIds(reads.countsByRun().keySet());

        return buildSummary(reads, thresholds);
    }

    private AnalyticsReads readAnalytics(List<UUID> suiteIds) {
        final List<SuiteRunRank> ranks = evalSummaryRepository.findLatestScoredRuns(suiteIds, RUNS_PER_SUITE);
        final List<UUID> runIds = ranks.stream().map(SuiteRunRank::runId).toList();
        final Map<UUID, UUID> computations = computationResolver.resolveLatest(runIds);
        final Map<UUID, RunPassCounts> counts = testCaseEvalScoreRepository.countByRunAndComputation(computations);
        return new AnalyticsReads(ranks, counts);
    }

    private SuitePassRateSummaryDto buildSummary(AnalyticsReads reads, Map<UUID, Double> thresholds) {
        final Map<UUID, List<SuiteRunRank>> runsBySuite = reads.ranks().stream()
                .filter(rank -> reads.countsByRun().containsKey(rank.runId()))
                .collect(Collectors.groupingBy(SuiteRunRank::suiteId));

        final List<Double> latestRates = new ArrayList<>();
        final List<Double> previousRates = new ArrayList<>();
        for (final List<SuiteRunRank> suiteRuns : runsBySuite.values()) {
            final List<SuiteRunRank> ordered =
                    suiteRuns.stream().sorted(Comparator.comparingInt(SuiteRunRank::rank)).toList();
            addRate(latestRates, ordered, 0, reads, thresholds);
            addRate(previousRates, ordered, 1, reads, thresholds);
        }

        return SuitePassRateSummaryDto.builder()
                .passRate(passRateCalculator.mean(latestRates))
                .suiteCount(latestRates.size())
                .previousPassRate(passRateCalculator.mean(previousRates))
                .previousSuiteCount(previousRates.size())
                .build();
    }

    /** Adds the pass rate of the run at {@code position} in the suite's ordered runs, if there is one. */
    private void addRate(
            List<Double> target,
            List<SuiteRunRank> ordered,
            int position,
            AnalyticsReads reads,
            Map<UUID, Double> thresholds) {
        if (position >= ordered.size()) {
            return;
        }
        final UUID runId = ordered.get(position).runId();
        final Double rate = passRateCalculator.rate(reads.countsByRun().get(runId), thresholds.containsKey(runId));
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

    private record AnalyticsReads(List<SuiteRunRank> ranks, Map<UUID, RunPassCounts> countsByRun) {}
}
