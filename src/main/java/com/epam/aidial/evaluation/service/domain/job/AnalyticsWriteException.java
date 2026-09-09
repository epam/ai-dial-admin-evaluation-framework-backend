package com.epam.aidial.evaluation.service.domain.job;

/**
 * Thrown when Phase 2 (metric evaluation) fails to flush results to the analytics datasource. Always
 * mapped to a terminal FAILED status by {@link TestSuiteEvaluationJob}, even when the run's
 * {@link RunHandle} has been cancelled — an analytics write failure must never be reported as a
 * cancellation.
 */
public class AnalyticsWriteException extends RuntimeException {

    public static final String ERROR_CODE = "ANALYTICS_WRITE_FAILED";

    public AnalyticsWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
