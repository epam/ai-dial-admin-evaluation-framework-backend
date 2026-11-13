package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TestCaseEvalScoreRepository {

    void saveAll(List<TestCaseEvalScore> scores);

    /**
     * Tallies each run's rows for the given computation in one statement, excluding soft-deleted runs.
     *
     * @param runToComputation run id to the computation whose rows are counted; an empty or {@code null} map
     *     returns an empty map without touching the database
     * @return one entry per run that has at least one row for its computation; a run with no rows is absent
     */
    Map<UUID, RunPassCounts> countByRunAndComputation(Map<UUID, UUID> runToComputation);
}
