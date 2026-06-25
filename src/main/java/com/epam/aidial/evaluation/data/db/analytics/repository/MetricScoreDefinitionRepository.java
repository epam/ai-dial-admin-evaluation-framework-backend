package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreDefinition;
import java.util.List;
import java.util.UUID;

public interface MetricScoreDefinitionRepository {

    /** All DEFAULT definitions plus the TEST_SUITE definitions scoped to {@code testSuiteId}. */
    List<MetricScoreDefinition> findApplicable(UUID testSuiteId);
}
