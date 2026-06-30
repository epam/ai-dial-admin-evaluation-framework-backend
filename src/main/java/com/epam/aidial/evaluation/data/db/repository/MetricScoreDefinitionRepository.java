package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.MetricScoreDefinition;
import java.util.List;

public interface MetricScoreDefinitionRepository {

    /** All seeded per-metric statistic definitions (AVG/P10/P90/MIN/MAX), ordered by name. */
    List<MetricScoreDefinition> findAll();
}
