package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.RevalidationTask;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.Optional;
import java.util.UUID;

public interface RevalidationTaskRepository {

    RevalidationTask save(RevalidationTask task);

    RevalidationTask update(RevalidationTask task);

    Optional<RevalidationTask> findById(UUID id);

    Optional<RevalidationTask> findByIdAndDatasetId(UUID id, UUID datasetId);

    Page<RevalidationTask> findAllByDatasetId(UUID datasetId, PageRequest pageRequest);
}
