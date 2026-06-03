package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.mapper.TestCaseRecordMapper;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.sql.BulkPatchFields;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.OrderByBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.PageRequestSqlBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.SortWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Query;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestCaseRepository implements TestCaseRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final TestCaseRecordMapper recordMapper;
    private final WhereBuilder whereBuilder;
    private final OrderByBuilder orderByBuilder;
    private final TransactionTimestampContext transactionTimestampContext;
    private final Clock clock;

    @Override
    public Page<TestCase> findAllByDatasetId(
            UUID datasetId, PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        Condition filterCondition =
                whereBuilder.build(filters != null ? filters : List.of(), FilterWhitelists.TEST_CASES);
        Condition combined = DSL.and(TEST_CASES.DATASET_ID.eq(datasetId.toString()), filterCondition);

        long totalCount = includeTotalCount ? countByCondition(combined) : -1;
        List<SortField<?>> orderBy = orderByBuilder.build(pageRequest.getSort(), SortWhitelists.TEST_CASES);

        int limit = PageRequestSqlBuilder.limit(pageRequest);
        long offset = PageRequestSqlBuilder.offset(pageRequest);

        List<TestCase> content = dsl.selectFrom(TEST_CASES)
                .where(combined)
                .orderBy(orderBy)
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);

        return includeTotalCount ? Page.of(content, pageRequest, totalCount) : Page.withoutTotal(content, pageRequest);
    }

    private long countByCondition(Condition condition) {
        Long count = dsl.selectCount().from(TEST_CASES).where(condition).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public Optional<TestCase> findByIdAndDatasetId(UUID id, UUID datasetId) {
        return dsl.selectFrom(TEST_CASES)
                .where(TEST_CASES.ID.eq(id.toString()).and(TEST_CASES.DATASET_ID.eq(datasetId.toString())))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public TestCase save(TestCase testCase) {
        long now = transactionTimestampContext.getTimestamp();
        if (testCase.getId() == null) {
            testCase.setId(UUID.randomUUID());
        }
        testCase.setCreatedAt(now);
        testCase.setUpdatedAt(now);

        dsl.insertInto(TEST_CASES)
                .set(TEST_CASES.ID, testCase.getId().toString())
                .set(TEST_CASES.DATASET_ID, testCase.getDatasetId().toString())
                .set(TEST_CASES.TEST_CASE_NAME, testCase.getTestCaseName())
                .set(TEST_CASES.DATA, toJsonb(testCase.getData()))
                .set(TEST_CASES.IS_VALID, testCase.isValid())
                .set(
                        TEST_CASES.VALIDATION_WARNINGS,
                        toJsonb(testCase.getValidationWarnings() != null ? testCase.getValidationWarnings() : "[]"))
                .set(TEST_CASES.CREATED_AT_MS, testCase.getCreatedAt())
                .set(TEST_CASES.UPDATED_AT_MS, testCase.getUpdatedAt())
                .execute();
        return testCase;
    }

    @Override
    public TestCase update(TestCase testCase) {
        long now = transactionTimestampContext.getTimestamp();
        testCase.setUpdatedAt(now);

        int updated = dsl.update(TEST_CASES)
                .set(TEST_CASES.TEST_CASE_NAME, testCase.getTestCaseName())
                .set(TEST_CASES.DATA, toJsonb(testCase.getData()))
                .set(TEST_CASES.IS_VALID, testCase.isValid())
                .set(
                        TEST_CASES.VALIDATION_WARNINGS,
                        toJsonb(testCase.getValidationWarnings() != null ? testCase.getValidationWarnings() : "[]"))
                .set(TEST_CASES.UPDATED_AT_MS, now)
                .where(TEST_CASES
                        .ID
                        .eq(testCase.getId().toString())
                        .and(TEST_CASES.DATASET_ID.eq(testCase.getDatasetId().toString())))
                .execute();
        return updated == 0 ? null : testCase;
    }

    @Override
    public List<TestCase> findAllByIdsAndDatasetId(Collection<UUID> ids, UUID datasetId) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> idStrings = ids.stream().map(UUID::toString).toList();
        return dsl.selectFrom(TEST_CASES)
                .where(TEST_CASES.ID.in(idStrings).and(TEST_CASES.DATASET_ID.eq(datasetId.toString())))
                .fetch(recordMapper::map);
    }

    @Override
    public void batchUpdate(List<TestCase> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            return;
        }
        long now = transactionTimestampContext.getTimestamp();
        List<Query> queries = testCases.stream()
                .map(tc -> {
                    tc.setUpdatedAt(now);
                    return (Query) dsl.update(TEST_CASES)
                            .set(TEST_CASES.TEST_CASE_NAME, tc.getTestCaseName())
                            .set(TEST_CASES.DATA, toJsonb(tc.getData()))
                            .set(TEST_CASES.IS_VALID, tc.isValid())
                            .set(
                                    TEST_CASES.VALIDATION_WARNINGS,
                                    toJsonb(tc.getValidationWarnings() != null ? tc.getValidationWarnings() : "[]"))
                            .set(TEST_CASES.UPDATED_AT_MS, now)
                            .where(TEST_CASES
                                    .ID
                                    .eq(tc.getId().toString())
                                    .and(TEST_CASES.DATASET_ID.eq(
                                            tc.getDatasetId().toString())));
                })
                .toList();
        dsl.batch(queries).execute();
    }

    @Override
    public List<String> findCollidingNamesByDatasetIdExcludingIds(
            UUID datasetId, Collection<UUID> excludeIds, Collection<String> lowercasedNames) {
        if (lowercasedNames == null || lowercasedNames.isEmpty()) {
            return List.of();
        }
        List<String> excludeIdStrings = excludeIds.stream().map(UUID::toString).toList();
        return dsl.select(DSL.lower(TEST_CASES.TEST_CASE_NAME))
                .from(TEST_CASES)
                .where(TEST_CASES
                        .DATASET_ID
                        .eq(datasetId.toString())
                        .and(TEST_CASES.ID.notIn(excludeIdStrings))
                        .and(DSL.lower(TEST_CASES.TEST_CASE_NAME).in(lowercasedNames)))
                .fetch(0, String.class);
    }

    @Override
    public boolean deleteByIdAndDatasetId(UUID id, UUID datasetId) {
        int deleted = dsl.deleteFrom(TEST_CASES)
                .where(TEST_CASES.ID.eq(id.toString()).and(TEST_CASES.DATASET_ID.eq(datasetId.toString())))
                .execute();
        return deleted > 0;
    }

    @Override
    public long deleteAllByDatasetId(UUID datasetId, List<FilterCondition> filters) {
        if (filters == null || filters.isEmpty()) {
            return dsl.deleteFrom(TEST_CASES)
                    .where(TEST_CASES.DATASET_ID.eq(datasetId.toString()))
                    .execute();
        }
        Condition filterCondition = whereBuilder.build(filters, FilterWhitelists.TEST_CASES);
        Condition combined = DSL.and(TEST_CASES.DATASET_ID.eq(datasetId.toString()), filterCondition);
        return dsl.delete(TEST_CASES).where(combined).execute();
    }

    @Override
    public long countByDatasetId(UUID datasetId) {
        Long count = dsl.selectCount()
                .from(TEST_CASES)
                .where(TEST_CASES.DATASET_ID.eq(datasetId.toString()))
                .fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public List<TestCase> findBatchByDatasetId(UUID datasetId, int offset, int limit) {
        return dsl.selectFrom(TEST_CASES)
                .where(TEST_CASES.DATASET_ID.eq(datasetId.toString()))
                .orderBy(TEST_CASES.CREATED_AT_MS.asc(), TEST_CASES.ID.asc())
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);
    }

    @Override
    public void updateValidation(UUID id, UUID datasetId, boolean valid, String validationWarningsJson) {
        dsl.update(TEST_CASES)
                .set(TEST_CASES.IS_VALID, valid)
                .set(
                        TEST_CASES.VALIDATION_WARNINGS,
                        toJsonb(validationWarningsJson != null ? validationWarningsJson : "[]"))
                .set(TEST_CASES.UPDATED_AT_MS, clock.millis())
                .where(TEST_CASES.ID.eq(id.toString()).and(TEST_CASES.DATASET_ID.eq(datasetId.toString())))
                .execute();
    }

    @Override
    public int updateDataIfUnchanged(
            UUID id, UUID datasetId, String dataJson, long expectedUpdatedAt, long newUpdatedAt) {
        return dsl.update(TEST_CASES)
                .set(TEST_CASES.DATA, toJsonb(dataJson != null ? dataJson : "{}"))
                .set(TEST_CASES.UPDATED_AT_MS, newUpdatedAt)
                .where(TEST_CASES
                        .ID
                        .eq(id.toString())
                        .and(TEST_CASES.DATASET_ID.eq(datasetId.toString()))
                        .and(TEST_CASES.UPDATED_AT_MS.eq(expectedUpdatedAt)))
                .execute();
    }

    @Override
    public int updateValidationIfUnchanged(
            UUID id, UUID datasetId, boolean isValid, String warningsJson, long expectedUpdatedAt, long newUpdatedAt) {
        return dsl.update(TEST_CASES)
                .set(TEST_CASES.IS_VALID, isValid)
                .set(TEST_CASES.VALIDATION_WARNINGS, toJsonb(warningsJson != null ? warningsJson : "[]"))
                .set(TEST_CASES.UPDATED_AT_MS, newUpdatedAt)
                .where(TEST_CASES
                        .ID
                        .eq(id.toString())
                        .and(TEST_CASES.DATASET_ID.eq(datasetId.toString()))
                        .and(TEST_CASES.UPDATED_AT_MS.eq(expectedUpdatedAt)))
                .execute();
    }

    @Override
    public List<TestCase> findValidByDatasetIdExcludingIds(
            UUID datasetId, Collection<UUID> excludedIds, int offset, int limit) {
        Condition combined = validNotExcludedCondition(datasetId, excludedIds);
        return dsl.selectFrom(TEST_CASES)
                .where(combined)
                .orderBy(TEST_CASES.CREATED_AT_MS.asc(), TEST_CASES.ID.asc())
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);
    }

    @Override
    public long countValidByDatasetIdExcludingIds(UUID datasetId, Collection<UUID> excludedIds) {
        Long count = dsl.selectCount()
                .from(TEST_CASES)
                .where(validNotExcludedCondition(datasetId, excludedIds))
                .fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * Builds the snapshot-phase predicate {@code dataset_id = ? AND is_valid = TRUE AND NOT (id = ANY(?::text[]))}.
     * The excluded-id collection is bound as a single Postgres text array parameter so the planner can
     * keep using index seeks even when the suite's disabledTestCaseIds list approaches its cap; inlining
     * a SQL {@code IN (...)} literal would blow past the statement-parameter limit and churn the plan
     * cache. Skips the array predicate entirely when {@code excludedIds} is empty.
     */
    private static Condition validNotExcludedCondition(UUID datasetId, Collection<UUID> excludedIds) {
        Condition base = TEST_CASES.DATASET_ID.eq(datasetId.toString()).and(TEST_CASES.IS_VALID.eq(true));
        if (excludedIds == null || excludedIds.isEmpty()) {
            return base;
        }
        String[] excludedStrings = excludedIds.stream().map(UUID::toString).toArray(String[]::new);
        Field<String[]> excludedArray = DSL.array(excludedStrings);
        return base.and(DSL.condition("NOT (test_cases.id = ANY({0}::text[]))", excludedArray));
    }

    @Override
    public int insertOrSkip(TestCase testCase) {
        long now = transactionTimestampContext.getTimestamp();
        if (testCase.getId() == null) {
            testCase.setId(UUID.randomUUID());
        }
        testCase.setCreatedAt(now);
        testCase.setUpdatedAt(now);
        return dsl.insertInto(TEST_CASES)
                .set(TEST_CASES.ID, testCase.getId().toString())
                .set(TEST_CASES.DATASET_ID, testCase.getDatasetId().toString())
                .set(TEST_CASES.TEST_CASE_NAME, testCase.getTestCaseName())
                .set(TEST_CASES.DATA, toJsonb(testCase.getData()))
                .set(TEST_CASES.IS_VALID, testCase.isValid())
                .set(
                        TEST_CASES.VALIDATION_WARNINGS,
                        toJsonb(testCase.getValidationWarnings() != null ? testCase.getValidationWarnings() : "[]"))
                .set(TEST_CASES.CREATED_AT_MS, testCase.getCreatedAt())
                .set(TEST_CASES.UPDATED_AT_MS, testCase.getUpdatedAt())
                .onConflict(DSL.field("dataset_id"), DSL.field("LOWER(test_case_name)"))
                .doNothing()
                .execute();
    }

    @Override
    public boolean insertOrOverride(TestCase testCase) {
        long now = transactionTimestampContext.getTimestamp();
        if (testCase.getId() == null) {
            testCase.setId(UUID.randomUUID());
        }
        testCase.setCreatedAt(now);
        testCase.setUpdatedAt(now);
        // RETURNING (xmax <> 0)::int requires plain SQL; jOOQ's onConflictDoUpdate does not
        // expose an expression-based RETURNING clause for custom expressions like xmax.
        Integer wasUpdate = dsl.resultQuery(
                        "INSERT INTO test_cases ("
                                + "id, dataset_id, test_case_name, data, is_valid, validation_warnings, "
                                + "created_at_ms, updated_at_ms"
                                + ") VALUES ("
                                + "{0}, {1}, {2}, {3}::jsonb, {4}, {5}::jsonb, {6}, {7}"
                                + ") ON CONFLICT (dataset_id, LOWER(test_case_name)) DO UPDATE SET"
                                + " test_case_name = EXCLUDED.test_case_name,"
                                + " data = EXCLUDED.data,"
                                + " is_valid = EXCLUDED.is_valid,"
                                + " validation_warnings = EXCLUDED.validation_warnings,"
                                + " updated_at_ms = EXCLUDED.updated_at_ms"
                                + " RETURNING (xmax <> 0)::int AS was_update",
                        DSL.val(testCase.getId().toString()),
                        DSL.val(testCase.getDatasetId().toString()),
                        DSL.val(testCase.getTestCaseName()),
                        DSL.val(testCase.getData() != null ? testCase.getData() : "{}"),
                        DSL.val(testCase.isValid()),
                        DSL.val(testCase.getValidationWarnings() != null ? testCase.getValidationWarnings() : "[]"),
                        DSL.val(testCase.getCreatedAt()),
                        DSL.val(testCase.getUpdatedAt()))
                .fetchOne(0, Integer.class);
        return wasUpdate != null && wasUpdate == 1;
    }

    @Override
    public void removeDataFields(UUID datasetId, Collection<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return;
        }
        // jOOQ has no typed DSL for the JSONB - text[] operator. Bind the field names as
        // an ARRAY[?, ?, ...] expression where every element is a parameter, not an inlined literal.
        Field<String[]> fieldsArray = DSL.array(fieldNames.toArray(String[]::new));
        dsl.execute(
                "UPDATE test_cases SET data = data - {0}::text[] WHERE dataset_id = {1}",
                fieldsArray, DSL.val(datasetId.toString()));
    }

    @Override
    public List<String> findExistingNamesByDatasetIdAndNamesLower(UUID datasetId, List<String> lowerNames) {
        if (lowerNames == null || lowerNames.isEmpty()) {
            return List.of();
        }
        return dsl.select(TEST_CASES.TEST_CASE_NAME)
                .from(TEST_CASES)
                .where(TEST_CASES
                        .DATASET_ID
                        .eq(datasetId.toString())
                        .and(DSL.lower(TEST_CASES.TEST_CASE_NAME).in(lowerNames)))
                .fetch(TEST_CASES.TEST_CASE_NAME);
    }

    @Override
    public void batchInsert(List<TestCase> testCases, long timestamp) {
        if (testCases == null || testCases.isEmpty()) {
            return;
        }
        List<Query> queries = testCases.stream()
                .map(tc -> (Query) dsl.insertInto(TEST_CASES)
                        .set(TEST_CASES.ID, tc.getId().toString())
                        .set(TEST_CASES.DATASET_ID, tc.getDatasetId().toString())
                        .set(TEST_CASES.TEST_CASE_NAME, tc.getTestCaseName())
                        .set(TEST_CASES.DATA, toJsonb(tc.getData()))
                        .set(TEST_CASES.IS_VALID, tc.isValid())
                        .set(
                                TEST_CASES.VALIDATION_WARNINGS,
                                toJsonb(tc.getValidationWarnings() != null ? tc.getValidationWarnings() : "[]"))
                        .set(TEST_CASES.CREATED_AT_MS, tc.getCreatedAt() != null ? tc.getCreatedAt() : timestamp)
                        .set(TEST_CASES.UPDATED_AT_MS, timestamp))
                .toList();
        dsl.batch(queries).execute();
    }

    @Override
    public int updateFieldsByIds(UUID datasetId, List<UUID> ids, Map<String, Object> setClause, long updatedAtMs) {
        if (ids == null || ids.isEmpty() || setClause == null || setClause.isEmpty()) {
            return 0;
        }

        var update = dsl.update(TEST_CASES);
        var step = update.set(TEST_CASES.UPDATED_AT_MS, updatedAtMs);
        Condition distinct = null;
        for (Map.Entry<String, Object> entry : setClause.entrySet()) {
            String column = BulkPatchFields.columnFor(entry.getKey());
            if (column == null) {
                throw new IllegalArgumentException("Field '" + entry.getKey() + "' is not in the bulk-patch whitelist");
            }
            switch (entry.getKey()) {
                case "testCaseName" -> {
                    String value =
                            entry.getValue() == null ? null : entry.getValue().toString();
                    step = step.set(TEST_CASES.TEST_CASE_NAME, value);
                    Condition c = TEST_CASES.TEST_CASE_NAME.isDistinctFrom(value);
                    distinct = distinct == null ? c : distinct.or(c);
                }
                case "data" -> {
                    JSONB jsonb = toJsonb((String) entry.getValue());
                    step = step.set(TEST_CASES.DATA, jsonb);
                    Condition c = TEST_CASES.DATA.isDistinctFrom(jsonb);
                    distinct = distinct == null ? c : distinct.or(c);
                }
                default ->
                    throw new IllegalArgumentException(
                            "Field '" + entry.getKey() + "' is whitelisted but unhandled by repository");
            }
        }

        List<String> idStrings = ids.stream().map(UUID::toString).toList();
        Condition where = TEST_CASES.DATASET_ID.eq(datasetId.toString()).and(TEST_CASES.ID.in(idStrings));
        if (distinct != null) {
            where = where.and(distinct);
        }
        return step.where(where).execute();
    }

    @Override
    public List<UUID> findIdsByDatasetIdAndFilter(UUID datasetId, List<FilterCondition> filters, int limit) {
        Condition filterCondition =
                whereBuilder.build(filters != null ? filters : List.of(), FilterWhitelists.TEST_CASES);
        Condition combined = DSL.and(TEST_CASES.DATASET_ID.eq(datasetId.toString()), filterCondition);

        List<String> idStrings = dsl.select(TEST_CASES.ID)
                .from(TEST_CASES)
                .where(combined)
                .limit(limit)
                .fetch(TEST_CASES.ID);
        List<UUID> result = new ArrayList<>(idStrings.size());
        for (String s : idStrings) {
            result.add(UUID.fromString(s));
        }
        return result;
    }

    @Override
    public List<UUID> findExistingIdsInDataset(UUID datasetId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> idStrings = ids.stream().map(UUID::toString).toList();
        List<String> rows = dsl.select(TEST_CASES.ID)
                .from(TEST_CASES)
                .where(TEST_CASES.DATASET_ID.eq(datasetId.toString()).and(TEST_CASES.ID.in(idStrings)))
                .fetch(TEST_CASES.ID);
        List<UUID> result = new ArrayList<>(rows.size());
        for (String s : rows) {
            result.add(UUID.fromString(s));
        }
        return result;
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
