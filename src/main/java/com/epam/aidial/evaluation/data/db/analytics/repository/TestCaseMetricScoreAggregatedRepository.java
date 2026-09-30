package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseMetricScoreAggregated;
import java.util.List;
import java.util.UUID;

public interface TestCaseMetricScoreAggregatedRepository {

    void saveAll(List<TestCaseMetricScoreAggregated> aggregates);

    List<TestCaseMetricScoreAggregated> findByRunIdAndComputationId(UUID runId, UUID computationId);
}
