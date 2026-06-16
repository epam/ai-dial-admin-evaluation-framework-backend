package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunMetricSnapshotRepository {

    void saveAll(List<RunMetricSnapshot> snapshots);

    List<RunMetricSnapshot> findByRunId(UUID runId);

    Optional<UUID> findLatestComputationId(UUID runId);
}
