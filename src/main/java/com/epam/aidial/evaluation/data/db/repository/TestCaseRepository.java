package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;

/**
 * Test case data access. Used by RevalidationService and TestCaseService.
 */
public interface TestCaseRepository {

    Page<TestCase> findAllByDatasetId(
            UUID datasetId, PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount);

    Optional<TestCase> findByIdAndDatasetId(UUID id, UUID datasetId);

    TestCase save(TestCase testCase);

    TestCase update(TestCase testCase);

    /**
     * Fetches all test cases matching the given IDs within the specified dataset.
     * The returned list order is not guaranteed to match the input order.
     */
    List<TestCase> findAllByIdsAndDatasetId(Collection<UUID> ids, UUID datasetId);

    /**
     * Batch-updates all given test cases using JDBC batching (single round-trip).
     * Sets {@code updatedAt} from {@link com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext}.
     */
    void batchUpdate(List<TestCase> testCases);

    /**
     * Returns the lowercased names of test cases in the given dataset that collide with the provided names,
     * excluding the specified IDs (the batch items themselves).
     */
    List<String> findCollidingNamesByDatasetIdExcludingIds(
            UUID datasetId, Collection<UUID> excludeIds, Collection<String> lowercasedNames);

    boolean deleteByIdAndDatasetId(UUID id, UUID datasetId);

    long deleteAllByDatasetId(UUID datasetId, List<FilterCondition> filters);

    long countByDatasetId(UUID datasetId);

    List<TestCase> findBatchByDatasetId(UUID datasetId, int offset, int limit);

    void updateValidation(UUID id, UUID datasetId, boolean valid, String validationWarningsJson);

    /**
     * Updates the {@code data} JSONB column only when the row's current {@code updated_at_ms}
     * matches {@code expectedUpdatedAt}. Used by the schema-change revalidation path to skip
     * rows that were edited concurrently.
     *
     * @return 1 if the row was updated, 0 if the precondition failed (concurrent edit)
     */
    int updateDataIfUnchanged(UUID id, UUID datasetId, String dataJson, long expectedUpdatedAt, long newUpdatedAt);

    /**
     * Updates {@code is_valid} and {@code validation_warnings} only when the row's current
     * {@code updated_at_ms} matches {@code expectedUpdatedAt}. Mirrors {@link #updateDataIfUnchanged}.
     *
     * @return 1 if the row was updated, 0 if the precondition failed (concurrent edit)
     */
    int updateValidationIfUnchanged(
            UUID id, UUID datasetId, boolean isValid, String warningsJson, long expectedUpdatedAt, long newUpdatedAt);

    /**
     * Returns the page of valid test cases in the given dataset, excluding any whose id is in {@code excludedIds}.
     * Used by the snapshot phase to project the suite's runnable test cases (dataset's valid rows minus the
     * suite's {@code disabledTestCaseIds}). Sorted by (createdAt asc, id asc) for deterministic paging.
     */
    List<TestCase> findValidByDatasetIdExcludingIds(
            UUID datasetId, Collection<UUID> excludedIds, int offset, int limit);

    /**
     * Returns the number of valid test cases in the given dataset, excluding any whose id is in {@code excludedIds}.
     * Used by the snapshot phase to seed {@code numberOfTestCases}.
     */
    long countValidByDatasetIdExcludingIds(UUID datasetId, Collection<UUID> excludedIds);

    /**
     * As {@link #findValidByDatasetIdExcludingIds} but additionally AND-ing {@code extraCondition}
     * (e.g. a suite {@code testCaseFilter} translated to jOOQ). A {@code null} condition imposes no
     * extra restriction (identical to {@link #findValidByDatasetIdExcludingIds}). The condition must
     * be expressed over the {@code test_cases} table.
     */
    List<TestCase> findValidByDatasetIdExcludingIdsMatching(
            UUID datasetId, Collection<UUID> excludedIds, Condition extraCondition, int offset, int limit);

    /**
     * As {@link #countValidByDatasetIdExcludingIds} but additionally AND-ing {@code extraCondition}
     * ({@code null} = no extra restriction). The condition must be expressed over the
     * {@code test_cases} table.
     */
    long countValidByDatasetIdExcludingIdsMatching(
            UUID datasetId, Collection<UUID> excludedIds, Condition extraCondition);

    /**
     * Inserts a test case, skipping if a row with the same (dataset_id, LOWER(test_case_name)) already exists.
     *
     * @return 1 if the row was inserted, 0 if it was skipped (name collision)
     */
    int insertOrSkip(TestCase testCase);

    /**
     * Inserts a test case, replacing an existing row if a name collision occurs (last wins).
     *
     * @return true if an existing row was replaced, false if this was a fresh insert
     */
    boolean insertOrOverride(TestCase testCase);

    /**
     * Removes the given field keys from the {@code data} JSONB column of all test cases
     * belonging to the specified dataset.
     */
    void removeDataFields(UUID datasetId, Collection<String> fieldNames);

    /**
     * Returns the test_case_name values (as stored) for rows whose {@code LOWER(test_case_name)}
     * matches any entry in {@code lowerNames} and belong to the given dataset.
     * Used for preview collision detection.
     */
    List<String> findExistingNamesByDatasetIdAndNamesLower(UUID datasetId, List<String> lowerNames);

    /**
     * Batch-inserts all given test cases using JDBC batching (single round-trip).
     * Uses the supplied {@code timestamp} for {@code created_at_ms} and {@code updated_at_ms};
     * does NOT call {@link com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext}.
     */
    void batchInsert(List<TestCase> testCases, long timestamp);

    /**
     * Updates the whitelisted columns named in {@code setClause} on every row whose id is in {@code ids}
     * and which belongs to {@code datasetId}. Returns the number of rows whose state actually changed
     * (NULL-safe comparison via {@code IS DISTINCT FROM}).
     *
     * @param setClause API-field → new-value (keys MUST match the bulk-patch whitelist)
     */
    int updateFieldsByIds(UUID datasetId, List<UUID> ids, Map<String, Object> setClause, long updatedAtMs);

    /**
     * Returns up to {@code limit} test-case ids in {@code datasetId} matching all given filter conditions.
     */
    List<UUID> findIdsByDatasetIdAndFilter(UUID datasetId, List<FilterCondition> filters, int limit);

    /**
     * Returns the subset of {@code ids} that exist in {@code datasetId}.
     */
    List<UUID> findExistingIdsInDataset(UUID datasetId, List<UUID> ids);

    /**
     * Deletes all test cases whose id is in {@code ids} and which belong to {@code datasetId}.
     * Uses a PostgreSQL {@code RETURNING id} clause to identify which rows were actually deleted.
     *
     * @return IDs of the rows that were deleted (order is not guaranteed to match input)
     */
    List<UUID> deleteByIdsAndDatasetId(UUID datasetId, List<UUID> ids);
}
