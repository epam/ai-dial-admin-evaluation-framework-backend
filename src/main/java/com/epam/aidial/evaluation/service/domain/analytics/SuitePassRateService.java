package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
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
 * <p>Per suite, the latest two recorded {@code COMPLETED} runs are used, each at its latest computation, with the
 * counts stored by {@link RunPassCountService} (pass rule: see {@code run-pass-counts}). Runs that are in flight,
 * failed or cancelled have no record and are never used; a completed run whose counts are not recorded yet is
 * ignored until they are. The summary is the mean of the per-suite rates (each suite counts once); a suite
 * without a previous run is left out of the previous mean only. A selected run whose record has a total of 0
 * contributes nothing for its position; it is not replaced by an older run.
 *
 * <p>Only the analytics datasource is queried, in one read-only transaction.
 */
@Slf4j
@Service
@LogExecution
public class SuitePassRateService {

    private static final int RUNS_PER_SUITE = 2;
    private static final int LATEST_POSITION = 0;
    private static final int PREVIOUS_POSITION = 1;

    private final RunPassCountService runPassCountService;
    private final PassRateCalculator passRateCalculator;
    private final TransactionTemplate analyticsTransactionTemplate;

    public SuitePassRateService(
            RunPassCountService runPassCountService,
            PassRateCalculator passRateCalculator,
            @Qualifier("analyticsTransactionManager") PlatformTransactionManager analyticsTxManager) {
        this.runPassCountService = runPassCountService;
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

        final Map<UUID, List<RunPassCount>> runsBySuite = analyticsTransactionTemplate.execute(
                status -> runPassCountService.getLatestRecordedRuns(distinctIds, RUNS_PER_SUITE));
        return buildSummary(runsBySuite);
    }

    private SuitePassRateSummaryDto buildSummary(Map<UUID, List<RunPassCount>> runsBySuite) {
        final List<Double> latestRates = new ArrayList<>();
        final List<Double> previousRates = new ArrayList<>();
        for (final List<RunPassCount> suiteRuns : runsBySuite.values()) {
            addRate(latestRates, suiteRuns, LATEST_POSITION);
            addRate(previousRates, suiteRuns, PREVIOUS_POSITION);
        }

        return SuitePassRateSummaryDto.builder()
                .passRate(passRateCalculator.mean(latestRates))
                .suiteCount(latestRates.size())
                .previousPassRate(passRateCalculator.mean(previousRates))
                .previousSuiteCount(previousRates.size())
                .build();
    }

    /** Adds the pass rate of the run at {@code position} in the suite's newest-first runs, if there is one. */
    private void addRate(List<Double> target, List<RunPassCount> suiteRuns, int position) {
        if (position >= suiteRuns.size()) {
            return;
        }
        final RunPassCount counts = suiteRuns.get(position);
        final Double rate = passRateCalculator.rate(counts.getPassedCount(), counts.getTotalCount());
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
