package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.job.RunnableTestCaseSelector;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Counts the runnable <b>individual</b> test cases of a suite — those that are valid
 * ({@code is_valid = true}), not in the suite's {@code disabledTestCaseIds}, and (when set) matching the
 * suite's {@code testCaseFilter}. MultiTurn turns are counted as individual rows here (no
 * per-multiTurn grouping); multiTurn integrity is resolved only at snapshot time. This is the
 * single place that reads the runnable count for the run-creation guard; it delegates to
 * {@link RunnableTestCaseSelector} so the filter is applied consistently with the snapshot phase.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class RunnableTestCaseCounter {

    private final RunnableTestCaseSelector runnableTestCaseSelector;

    public long countRunnable(UUID datasetId, String filterJson, Collection<UUID> disabledTestCaseIds) {
        return runnableTestCaseSelector.countRunnable(
                datasetId, filterJson, disabledTestCaseIds != null ? disabledTestCaseIds : List.of());
    }

    /**
     * Whether the dataset contains any multi-turn multiTurn row. The run-creation guard uses this to
     * reject MCP suites bound to a dataset carrying multiTurn rows (multi-turn is HTTP-deployment only).
     */
    public boolean hasMultiTurnRows(UUID datasetId) {
        return runnableTestCaseSelector.datasetHasMultiTurnRows(datasetId);
    }
}
