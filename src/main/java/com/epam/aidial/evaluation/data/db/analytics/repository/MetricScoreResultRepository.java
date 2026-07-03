package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import java.util.List;
import java.util.UUID;

public interface MetricScoreResultRepository {

    void saveAll(List<MetricScoreResult> results);

    List<MetricScoreResult> findByRunAndComputation(UUID runId, UUID computationId);
}
