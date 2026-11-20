package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.RUN_PASS_COUNTS;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.data.db.analytics.model.PassCountTotals;
import com.epam.aidial.evaluation.data.db.analytics.model.RecordedComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectField;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresRunPassCountRepository implements RunPassCountRepository {

    private static final String TEST_CASE_ID = "test_case_id";
    private static final String ALL_SUCCESS = "all_success";
    private static final String STATUS = "execution_status";
    private static final String PASSED = "passed";
    private static final String PER_RUN = "per_run";
    private static final String RANKED = "ranked";
    private static final String COMPUTATION_RANK = "computation_rank";
    private static final String SUITE_RANK = "suite_rank";

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    /**
     * Writer-side, not tombstone-filtered. One row per test case: the distinct test cases of the eval summaries
     * (with whether all their rows are SUCCESS) full-joined with the score rows.
     */
    @Override
    public PassCountTotals countForComputation(UUID runId, UUID computationId) {
        final String run = runId.toString();
        final String computation = computationId.toString();
        final Table<?> cases = dsl.select(
                        TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID.as(TEST_CASE_ID),
                        DSL.boolAnd(TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS.eq(ExecutionStatus.SUCCESS.name()))
                                .as(ALL_SUCCESS))
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.eq(run))
                .and(TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID.eq(computation))
                .groupBy(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID)
                .asTable("cases");
        final Table<?> scores = dsl.select(
                        TEST_CASE_EVAL_SCORES.TEST_CASE_ID.as(TEST_CASE_ID),
                        TEST_CASE_EVAL_SCORES.EXECUTION_STATUS.as(STATUS),
                        TEST_CASE_EVAL_SCORES.PASSED.as(PASSED))
                .from(TEST_CASE_EVAL_SCORES)
                .where(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID.eq(run))
                .and(TEST_CASE_EVAL_SCORES.COMPUTATION_ID.eq(computation))
                .asTable("scores");

        final Field<Boolean> allSuccess = cases.field(ALL_SUCCESS, Boolean.class);
        final Field<String> status = scores.field(STATUS, String.class);
        final Field<Boolean> passed = scores.field(PASSED, Boolean.class);
        final Condition casePasses = allSuccess
                .isNull()
                .or(allSuccess.isTrue())
                .and(status.isNull().or(status.eq(ExecutionStatus.SUCCESS.name())))
                .and(passed.isNull().or(passed.isTrue()));

        final Record result = dsl.select(DSL.count().filterWhere(casePasses), DSL.count())
                .from(cases)
                .fullOuterJoin(scores)
                .on(cases.field(TEST_CASE_ID, String.class).eq(scores.field(TEST_CASE_ID, String.class)))
                .fetchOne();
        return new PassCountTotals(result.get(0, Integer.class), result.get(1, Integer.class));
    }

    @Override
    public Optional<Long> findComputedAtMs(UUID runId, UUID computationId) {
        final Long fromSummaries = dsl.select(DSL.max(TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS))
                .from(TEST_CASE_EVAL_SUMMARIES)
                .where(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .and(TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID.eq(computationId.toString()))
                .fetchOne(0, Long.class);
        if (fromSummaries != null) {
            return Optional.of(fromSummaries);
        }
        return Optional.ofNullable(dsl.select(DSL.max(TEST_CASE_EVAL_SCORES.COMPUTED_AT_MS))
                .from(TEST_CASE_EVAL_SCORES)
                .where(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .and(TEST_CASE_EVAL_SCORES.COMPUTATION_ID.eq(computationId.toString()))
                .fetchOne(0, Long.class));
    }

    /**
     * No conflict target: the unique index is an expression index on {@code COALESCE(computation_id, '')}, which a
     * column-list conflict target does not match.
     */
    @Override
    public boolean insertIfAbsent(RunPassCount row) {
        final int inserted = dsl.insertInto(RUN_PASS_COUNTS)
                .set(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID, row.getTestSuiteRunId().toString())
                .set(RUN_PASS_COUNTS.TEST_SUITE_ID, row.getTestSuiteId().toString())
                .set(
                        RUN_PASS_COUNTS.COMPUTATION_ID,
                        row.getComputationId() != null ? row.getComputationId().toString() : null)
                .set(RUN_PASS_COUNTS.RUN_CREATED_AT_MS, row.getRunCreatedAtMs())
                .set(RUN_PASS_COUNTS.PASSED_COUNT, row.getPassedCount())
                .set(RUN_PASS_COUNTS.TOTAL_COUNT, row.getTotalCount())
                .set(RUN_PASS_COUNTS.COMPUTED_AT_MS, row.getComputedAtMs())
                .onConflictDoNothing()
                .execute();
        return inserted > 0;
    }

    @Override
    public List<RecordedComputation> findRecordedComputations(Collection<UUID> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        final String[] ids = runIds.stream().map(UUID::toString).toArray(String[]::new);
        return dsl.select(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID, RUN_PASS_COUNTS.COMPUTATION_ID)
                .from(RUN_PASS_COUNTS)
                .where(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID.eq(DSL.any(ids)))
                .fetch(r -> new RecordedComputation(
                        UUID.fromString(r.value1()), r.value2() != null ? UUID.fromString(r.value2()) : null));
    }

    @Override
    public Map<UUID, List<RunPassCount>> findLatestRanked(Collection<UUID> suiteIds, int perSuiteLimit) {
        if (suiteIds == null || suiteIds.isEmpty()) {
            return Map.of();
        }
        final Table<?> ranked = rankedPerSuite(suiteIdCondition(suiteIds));
        final Map<UUID, List<RunPassCount>> bySuite = new LinkedHashMap<>();
        dsl.select(projection(ranked))
                .from(ranked)
                .where(ranked.field(SUITE_RANK, Integer.class).le(perSuiteLimit))
                .orderBy(
                        ranked.field(RUN_PASS_COUNTS.TEST_SUITE_ID),
                        ranked.field(SUITE_RANK, Integer.class).asc())
                .forEach(r -> {
                    final RunPassCount row = toModel(r, ranked);
                    bySuite.computeIfAbsent(row.getTestSuiteId(), k -> new ArrayList<>())
                            .add(row);
                });
        return bySuite;
    }

    @Override
    public Page<RunPassCount> findLatestPage(Collection<UUID> suiteIds, PageRequest pageRequest) {
        if (suiteIds != null && suiteIds.isEmpty()) {
            return Page.empty(pageRequest);
        }
        final Condition scope = suiteIds == null ? DSL.noCondition() : suiteIdCondition(suiteIds);
        final Table<?> ranked = rankedPerSuite(scope);
        final Condition latestOnly = ranked.field(SUITE_RANK, Integer.class).eq(1);

        final Field<Integer> passed = ranked.field(RUN_PASS_COUNTS.PASSED_COUNT);
        final Field<Integer> total = ranked.field(RUN_PASS_COUNTS.TOTAL_COUNT);
        final Field<Double> rate =
                DSL.field("cast({0} as double precision) / nullif({1}, 0)", Double.class, passed, total);
        final List<SortField<?>> order = List.of(
                rate.asc().nullsLast(),
                ranked.field(RUN_PASS_COUNTS.RUN_CREATED_AT_MS).desc(),
                ranked.field(RUN_PASS_COUNTS.TEST_SUITE_ID).asc());

        final long totalElements = dsl.fetchCount(dsl.selectOne().from(ranked).where(latestOnly));
        final List<RunPassCount> content = dsl.select(projection(ranked))
                .from(ranked)
                .where(latestOnly)
                .orderBy(order)
                .limit(pageRequest.getValidatedSize())
                .offset(pageRequest.getOffset())
                .fetch(r -> toModel(r, ranked));
        return Page.of(content, pageRequest, totalElements);
    }

    private static Condition suiteIdCondition(Collection<UUID> suiteIds) {
        final String[] ids = suiteIds.stream().map(UUID::toString).toArray(String[]::new);
        return RUN_PASS_COUNTS.TEST_SUITE_ID.eq(DSL.any(ids));
    }

    /**
     * Tombstone-filtered rows reduced to each run's latest computation (null computation last, then greatest
     * {@code computed_at_ms}, then smallest computation id), then ranked per suite by run creation descending, run id
     * descending. Column fields keep their generated identity so callers resolve them with {@code field(...)}.
     */
    private Table<?> rankedPerSuite(Condition scope) {
        final List<SelectField<?>> perRunColumns = new ArrayList<>(List.of(RUN_PASS_COUNTS.fields()));
        perRunColumns.add(DSL.rowNumber()
                .over(DSL.partitionBy(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID)
                        .orderBy(
                                DSL.when(RUN_PASS_COUNTS.COMPUTATION_ID.isNull(), 1)
                                        .otherwise(0)
                                        .asc(),
                                RUN_PASS_COUNTS.COMPUTED_AT_MS.desc(),
                                RUN_PASS_COUNTS.COMPUTATION_ID.asc()))
                .as(COMPUTATION_RANK));
        final Table<?> perRun = dsl.select(perRunColumns)
                .from(RUN_PASS_COUNTS)
                .where(scope)
                .and(RunDeletionFilter.notDeleted(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID))
                .asTable(PER_RUN);
        final List<SelectField<?>> latestRunColumns = new ArrayList<>();
        for (final Field<?> f : RUN_PASS_COUNTS.fields()) {
            latestRunColumns.add(perRun.field(f));
        }
        latestRunColumns.add(DSL.rowNumber()
                .over(DSL.partitionBy(perRun.field(RUN_PASS_COUNTS.TEST_SUITE_ID))
                        .orderBy(
                                perRun.field(RUN_PASS_COUNTS.RUN_CREATED_AT_MS).desc(),
                                perRun.field(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID).desc()))
                .as(SUITE_RANK));
        return dsl.select(latestRunColumns)
                .from(perRun)
                .where(perRun.field(COMPUTATION_RANK, Integer.class).eq(1))
                .asTable(RANKED);
    }

    private static List<SelectField<?>> projection(Table<?> ranked) {
        final List<SelectField<?>> fields = new ArrayList<>();
        for (final Field<?> f : RUN_PASS_COUNTS.fields()) {
            fields.add(ranked.field(f));
        }
        return fields;
    }

    private static RunPassCount toModel(Record r, Table<?> ranked) {
        final String computation = r.get(ranked.field(RUN_PASS_COUNTS.COMPUTATION_ID));
        return RunPassCount.builder()
                .testSuiteRunId(UUID.fromString(r.get(ranked.field(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID))))
                .testSuiteId(UUID.fromString(r.get(ranked.field(RUN_PASS_COUNTS.TEST_SUITE_ID))))
                .computationId(computation != null ? UUID.fromString(computation) : null)
                .runCreatedAtMs(r.get(ranked.field(RUN_PASS_COUNTS.RUN_CREATED_AT_MS)))
                .passedCount(r.get(ranked.field(RUN_PASS_COUNTS.PASSED_COUNT)))
                .totalCount(r.get(ranked.field(RUN_PASS_COUNTS.TOTAL_COUNT)))
                .computedAtMs(r.get(ranked.field(RUN_PASS_COUNTS.COMPUTED_AT_MS)))
                .build();
    }
}
