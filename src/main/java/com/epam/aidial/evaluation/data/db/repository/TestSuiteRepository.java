package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSummary;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestSuiteRepository {

    Page<TestSuite> findAll(PageRequest pageRequest);

    Page<TestSuite> findAll(PageRequest pageRequest, boolean includeTotalCount);

    Page<TestSuite> findAll(PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount);

    Optional<TestSuite> findById(UUID id);

    TestSuite save(TestSuite testSuite);

    long count();

    boolean deleteById(UUID id);

    boolean existsById(UUID id);

    // NOTE: currently called from tests only (MetaTestDataHelper.forceSuiteInvalid)
    void updateIsValid(UUID id, boolean isValid);

    /**
     * Used by RevalidationService Phase 2 to write the (isValid, validationWarnings, updatedAt) tuple
     * for a suite atomically. The caller passes {@code updatedAt} explicitly so the method is safe to
     * invoke from contexts that do or do not have {@link com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext}
     * initialised (e.g. programmatic transactions, tests).
     */
    void updateValidation(UUID id, boolean isValid, String validationWarningsJson, long updatedAt);

    /**
     * Returns every {@link TestSuite} whose {@code dataset_id} equals the supplied id.
     * Used by {@code RevalidationService} Phase 2 for the dataset → suites fan-out.
     */
    List<TestSuite> findSuitesReferencingDataset(UUID datasetId);

    /**
     * Returns a lightweight {@link TestSuiteSummary} ({@code id}, {@code name}, {@code description})
     * for every suite whose {@code dataset_id} equals the supplied id. Uses a selective column
     * projection so the suite's large JSONB columns are not fetched or TOAST-decompressed. Used by
     * the dataset → dependent-suites listing endpoint.
     */
    List<TestSuiteSummary> findSuiteSummariesReferencingDataset(UUID datasetId);

    /**
     * Sets {@code test_suites.dataset_id = NULL} for every suite currently bound to the given
     * dataset. Returns the number of rows updated. Used by the PRIVATE-dataset delete flow
     * (both the direct dataset delete and the suite-triggered cascade) to detach suites
     * before the dataset row is removed.
     */
    int unbindAllByDatasetId(UUID datasetId);

    /**
     * Counts suites whose {@code dataset_id} equals the supplied id. Used by the dataset
     * visibility transition path to validate the PUBLIC→PRIVATE precondition (exactly 1
     * bound suite) under the dataset row lock.
     */
    long countByDatasetId(UUID datasetId);

    /**
     * Inserts a new test suite using the caller-supplied UUID and timestamp.
     * Unlike {@link #save(TestSuite)}, this method does NOT generate a new UUID or call
     * {@link com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext}.
     * The entity's {@code id} field must be non-null.
     */
    TestSuite createWithId(TestSuite testSuite, long timestamp);

    /**
     * Rebinds the suite to {@code newDatasetId}, replaces its {@code disabled_test_case_ids} with the
     * caller-supplied (remapped) JSON array, and bumps {@code version} and {@code updated_at_ms}.
     * Used by the detach-dataset flow inside the meta transaction: the disabled IDs must be remapped
     * to the cloned test cases and persisted together with the new binding, otherwise the suite would
     * keep referencing the source dataset's (now-orphaned) test-case IDs.
     */
    void updateDatasetId(UUID suiteId, UUID newDatasetId, String disabledTestCaseIds, long updatedAt);
}
