package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * The pass rate of one run's stored counts, and the mean across runs. Pure: no I/O. The pass rule itself lives in
 * the counting query of {@code run-pass-counts}.
 */
@Component
@LogExecution
public class PassRateCalculator {

    /** Pass rate in {@code [0, 1]}, or {@code null} when the run has no test cases. */
    public Double rate(long passed, long total) {
        if (total == 0) {
            return null;
        }
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
