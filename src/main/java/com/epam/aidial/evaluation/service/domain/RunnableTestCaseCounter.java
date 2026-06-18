package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Counts the runnable test cases of a dataset — those that are valid ({@code is_valid = true}) and
 * enabled (not in the suite's {@code disabledTestCaseIds}). This is the single place that reads the
 * test-case count for suite-validity purposes, so suite-domain callers depend on this service rather
 * than reaching into {@link TestCaseRepository} directly.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class RunnableTestCaseCounter {

    private final TestCaseRepository testCaseRepository;

    public long countRunnable(UUID datasetId, Collection<UUID> disabledTestCaseIds) {
        return testCaseRepository.countValidByDatasetIdExcludingIds(
                datasetId, disabledTestCaseIds != null ? disabledTestCaseIds : List.of());
    }
}
