package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * The pass-rate rule for one run, and the mean across runs. Pure: no I/O.
 *
 * <p>A run's pass rate is passed test cases over all test cases of its computation:
 * <ul>
 *   <li>run has a threshold — only {@code passed = true} counts; a SUCCESS test case with no {@code passed}
 *       value (unscored) and any non-SUCCESS test case do not;</li>
 *   <li>run has no threshold — {@code passed} is never set, so every SUCCESS test case counts.</li>
 * </ul>
 */
@Component
@LogExecution
public class PassRateCalculator {

    /** Pass rate in {@code [0, 1]}, or {@code null} when the run has no rows. */
    public Double rate(RunPassCounts counts, boolean hasThreshold) {
        final long total = counts.total();
        if (total == 0) {
            return null;
        }
        final long passed = hasThreshold
                ? counts.passedTrue()
                : counts.passedTrue() + counts.passedFalse() + counts.unscored();
        return (double) passed / total;
    }

    /** Arithmetic mean of {@code rates}, or {@code null} when there are none. */
    public Double mean(Collection<Double> rates) {
        if (rates == null || rates.isEmpty()) {
            return null;
        }
        return rates.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }
}
