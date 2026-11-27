package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.RunnerValidationConstants;
import org.springframework.stereotype.Component;

/**
 * Builds the name of a run started by a schedule. A schedule fires with the same configured {@code testRunName} every
 * time, and run names are unique per suite, so a plain reuse would fail every firing after the first. The sequence value
 * makes each name unique and the name stays recognisable in the list.
 */
@Component
@LogExecution
public class ScheduledRunNameFormatter {

    private static final String SEQUENCE_PREFIX = " #";

    /**
     * Returns {@code "<base> #<sequenceValue>"}, truncating the base so the result fits the run-name column.
     */
    public String format(String base, long sequenceValue) {
        final String suffix = SEQUENCE_PREFIX + sequenceValue;
        final int maxBaseLength = RunnerValidationConstants.MAX_TEST_RUN_NAME_LENGTH - suffix.length();
        final String trimmedBase = base.length() > maxBaseLength ? base.substring(0, maxBaseLength) : base;
        return trimmedBase + suffix;
    }
}
