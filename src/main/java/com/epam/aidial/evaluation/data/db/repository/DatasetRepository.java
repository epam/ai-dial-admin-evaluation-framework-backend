package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasetRepository {

    Page<Dataset> findAll(PageRequest pageRequest);

    Page<Dataset> findAll(PageRequest pageRequest, boolean includeTotalCount);

    Page<Dataset> findAll(PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount);

    Optional<Dataset> findById(UUID id);

    /**
     * Like {@link #findById(UUID)} but acquires a row-level {@code FOR UPDATE} lock on
     * the dataset row. Callers MUST be inside a meta transaction. Used by the visibility
     * transition path so the binding-count read and visibility write serialize against
     * concurrent suite-binding writes (which take the same lock via the trigger).
     */
    Optional<Dataset> findByIdForUpdate(UUID id);

    Dataset save(Dataset dataset);

    long count();

    boolean deleteById(UUID id);

    boolean existsById(UUID id);

    void updateIsValid(UUID id, boolean isValid);

    /**
     * Updates {@code visibility} and bumps {@code version}. No optimistic-locking
     * version check — callers use {@link #findByIdForUpdate(UUID)} to serialize.
     */
    void updateVisibility(UUID id, DatasetVisibility visibility, long updatedAt);

    /**
     * Updates {@code test_case_schema} and bumps {@code version} without optimistic locking.
     * Used by CSV import to persist auto-detected or merged schemas.
     */
    void updateTestCaseSchema(UUID id, String schemaJson);

    /**
     * Inserts a dataset with a caller-supplied {@code id} and {@code timestamp}, bypassing
     * UUID generation and {@link com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext}.
     * The entity's {@code id} must be non-null.
     */
    Dataset createWithId(Dataset dataset, long timestamp);
}
