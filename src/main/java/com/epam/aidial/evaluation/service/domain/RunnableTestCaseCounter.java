package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.job.RunnableTestCaseSelector;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Counts the runnable test cases of a suite — those that are valid ({@code is_valid = true}), not in
 * the suite's {@code disabledTestCaseIds}, and (when set) matching the suite's {@code testCaseFilter}.
 * This is the single place that reads the runnable count for the run-creation guard; it delegates to
 * {@link RunnableTestCaseSelector} so the filter is applied consistently with the snapshot phase.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class RunnableTestCaseCounter {

    private final RunnableTestCaseSelector runnableTestCaseSelector;

    public long countRunnable(UUID datasetId, String filterJson, Collection<UUID> disabledTestCaseIds) {
        return runnableTestCaseSelector.countRunnableUnits(
                datasetId, filterJson, disabledTestCaseIds != null ? disabledTestCaseIds : List.of());
    }

    /**
     * Whether the dataset contains any multi-turn conversation row. The run-creation guard uses this to
     * reject MCP suites bound to a dataset carrying conversation rows (multi-turn is HTTP-deployment only).
     */
    public boolean hasConversationRows(UUID datasetId) {
        return runnableTestCaseSelector.datasetHasConversationRows(datasetId);
    }
}
