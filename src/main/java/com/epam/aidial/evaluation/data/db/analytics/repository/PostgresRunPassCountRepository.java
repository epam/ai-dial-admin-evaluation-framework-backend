package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.RUN_PASS_COUNTS;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SCORES;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.constants.AnalyticsConstants;
import com.epam.aidial.evaluation.data.db.analytics.mapper.RunPassCountRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.PassCountTotals;
import com.epam.aidial.evaluation.data.db.analytics.model.RecordedComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.analytics.model.WeeklySuiteHealth;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.CommonTableExpression;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.SelectField;
import org.jooq.SelectFromStep;
import org.jooq.SelectSelectStep;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
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
    private static final String COMPUTATION_RANK = "computation_rank";
    private static final String TOTAL_ELEMENTS = "total_elements";
    private static final String SUITES_ALIAS = "s";
    private static final String LATEST_ALIAS = "l";
    private static final String TEST_SUITE_ID_COLUMN = "test_suite_id";
    private static final String SKIP_SCAN_CTE = "suite_skip_scan";
    private static final Table<?> SKIP_SCAN_CTE_TABLE = DSL.table(DSL.name(SKIP_SCAN_CTE));
    private static final String PER_SUITE_WEEK = "per_suite_week";
    private static final String WEEK_INDEX = "week_index";
    private static final String PASSED_SUM = "passed";
    private static final String TOTAL_SUM = "total";
    private static final String HEALTHY_COUNT = "healthy_count";
    private static final String FAILING_COUNT = "failing_count";

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final RunPassCountRecordMapper recordMapper;

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
        if (suiteIds == null || suiteIds.isEmpty() || perSuiteLimit <= 0) {
            return Map.of();
        }
        final Table<?> suites = unnestedSuites(suiteIds);
        final Table<?> latest = latestRunsLateral(suites, perSuiteLimit, true);
        final Map<UUID, List<RunPassCount>> bySuite = new LinkedHashMap<>();
        dsl.select(projection(latest))
                .from(suites)
                .crossJoin(latest)
                .orderBy(
                        latest.field(RUN_PASS_COUNTS.TEST_SUITE_ID).asc(),
                        latest.field(RUN_PASS_COUNTS.RUN_CREATED_AT_MS).desc(),
                        latest.field(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID).desc())
                .forEach(r -> {
                    final RunPassCount row = recordMapper.map(r, latest);
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
        final Table<?> suites = suiteIds == null ? allSuites() : unnestedSuites(suiteIds);
        final Table<?> latest = latestRunsLateral(suites, 1, false);

        final Field<Integer> passed = latest.field(RUN_PASS_COUNTS.PASSED_COUNT);
        final Field<Integer> total = latest.field(RUN_PASS_COUNTS.TOTAL_COUNT);
        final Field<Double> rate =
                DSL.field("cast({0} as double precision) / nullif({1}, 0)", Double.class, passed, total);
        final Field<Integer> totalElements = DSL.count().over().as(TOTAL_ELEMENTS);
        final List<SelectField<?>> columns = new ArrayList<>(projection(latest));
        columns.add(totalElements);

        final List<Record> rows = dsl.select(columns)
                .from(suites)
                .crossJoin(latest)
                .orderBy(
                        rate.asc().nullsLast(),
                        latest.field(RUN_PASS_COUNTS.RUN_CREATED_AT_MS).desc(),
                        latest.field(RUN_PASS_COUNTS.TEST_SUITE_ID).asc())
                .limit(pageRequest.getValidatedSize())
                .offset(pageRequest.getOffset())
                .fetch();
        final List<RunPassCount> content =
                rows.stream().map(r -> recordMapper.map(r, latest)).toList();
        return Page.of(content, pageRequest, totalOf(rows, totalElements, suites, latest, pageRequest));
    }

    /**
     * The total comes from the window count of any returned row. An empty page carries none: offset 0 means no
     * suite at all, a page past the end needs one separate count to keep reporting the real total.
     */
    private long totalOf(
            List<Record> rows,
            Field<Integer> totalElements,
            Table<?> suites,
            Table<?> latest,
            PageRequest pageRequest) {
        if (!rows.isEmpty()) {
            return rows.getFirst().get(totalElements);
        }
        if (pageRequest.getOffset() == 0) {
            return 0L;
        }
        return dsl.fetchCount(dsl.selectOne().from(suites).crossJoin(latest));
    }

    private static Condition suiteIdCondition(Collection<UUID> suiteIds) {
        final String[] ids = suiteIds.stream().map(UUID::toString).toArray(String[]::new);
        return RUN_PASS_COUNTS.TEST_SUITE_ID.eq(DSL.any(ids));
    }

    /** The distinct requested ids as a one-column table; a duplicated input id must not duplicate rows. */
    private static Table<?> unnestedSuites(Collection<UUID> suiteIds) {
        final String[] ids = suiteIds.stream().map(UUID::toString).distinct().toArray(String[]::new);
        return DSL.unnest(ids).asTable(SUITES_ALIAS, TEST_SUITE_ID_COLUMN);
    }

    /**
     * Every suite with a stored row, found by a recursive skip scan over the suite-first index (one probe per
     * suite) instead of a scan of every row. The suite list is not tombstone-filtered: a suite whose runs are all
     * deleted yields no row from the lateral and drops out.
     */
    private Table<?> allSuites() {
        final Field<String> suiteColumn = DSL.field(DSL.name(TEST_SUITE_ID_COLUMN), String.class);
        final Field<String> previous = DSL.field(DSL.name(SKIP_SCAN_CTE, TEST_SUITE_ID_COLUMN), String.class);
        final CommonTableExpression<Record1<String>> skipScan = DSL.name(SKIP_SCAN_CTE)
                .fields(TEST_SUITE_ID_COLUMN)
                .as(dsl.select(RUN_PASS_COUNTS.TEST_SUITE_ID)
                        .from(RUN_PASS_COUNTS)
                        .orderBy(RUN_PASS_COUNTS.TEST_SUITE_ID)
                        .limit(1)
                        .unionAll(dsl.select(DSL.field(dsl.select(RUN_PASS_COUNTS.TEST_SUITE_ID)
                                        .from(RUN_PASS_COUNTS)
                                        .where(RUN_PASS_COUNTS.TEST_SUITE_ID.gt(previous))
                                        .orderBy(RUN_PASS_COUNTS.TEST_SUITE_ID)
                                        .limit(1)))
                                .from(SKIP_SCAN_CTE_TABLE)
                                .where(previous.isNotNull())));
        return dsl.withRecursive(skipScan)
                .select(suiteColumn)
                .from(skipScan)
                .where(suiteColumn.isNotNull())
                .asTable(SUITES_ALIAS);
    }

    /**
     * Per suite, its newest runs each at the run's latest computation, tombstone-filtered, newest first, at most
     * {@code perSuiteLimit}. With {@code onePerRun}, {@code DISTINCT ON} keeps one row per run before the limit so a
     * recomputed run does not take several slots; a limit of one needs no such step.
     */
    private Table<?> latestRunsLateral(Table<?> suites, int perSuiteLimit, boolean onePerRun) {
        final List<SortField<?>> order = new ArrayList<>();
        order.add(RUN_PASS_COUNTS.RUN_CREATED_AT_MS.desc());
        order.add(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID.desc());
        order.addAll(computationOrder());
        final SelectSelectStep<Record> select = dsl.select(RUN_PASS_COUNTS.fields());
        final SelectFromStep<Record> from = onePerRun
                ? select.distinctOn(RUN_PASS_COUNTS.RUN_CREATED_AT_MS, RUN_PASS_COUNTS.TEST_SUITE_RUN_ID)
                : select;
        return DSL.lateral(from.from(RUN_PASS_COUNTS)
                        .where(RUN_PASS_COUNTS
                                .TEST_SUITE_ID
                                .eq(suites.field(TEST_SUITE_ID_COLUMN, String.class))
                                .and(RunDeletionFilter.notDeleted(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID)))
                        .orderBy(order)
                        .limit(perSuiteLimit))
                .asTable(LATEST_ALIAS);
    }

    /** A run's computations, best first: null computation last, then greatest {@code computed_at_ms}, then id. */
    private static List<SortField<?>> computationOrder() {
        return List.of(
                DSL.when(RUN_PASS_COUNTS.COMPUTATION_ID.isNull(), 1)
                        .otherwise(0)
                        .asc(),
                RUN_PASS_COUNTS.COMPUTED_AT_MS.desc(),
                RUN_PASS_COUNTS.COMPUTATION_ID.asc());
    }

    @Override
    public List<WeeklySuiteHealth> findWeeklySuiteHealth(
            Collection<UUID> suiteIds, long startMs, long endMs, double threshold) {
        if (suiteIds != null && suiteIds.isEmpty()) {
            return List.of();
        }
        final Condition suiteScope = suiteIds == null ? DSL.noCondition() : suiteIdCondition(suiteIds);
        final Condition scope = suiteScope
                .and(RUN_PASS_COUNTS.RUN_CREATED_AT_MS.ge(startMs))
                .and(RUN_PASS_COUNTS.RUN_CREATED_AT_MS.lt(endMs));
        final Table<?> perRun = computationRankedPerRun(scope);
        final Field<Long> weekIndex = perRun.field(RUN_PASS_COUNTS.RUN_CREATED_AT_MS)
                .sub(DSL.inline(startMs))
                .div(DSL.inline(AnalyticsConstants.WEEK_MS));
        final Field<BigDecimal> passedSum = DSL.sum(perRun.field(RUN_PASS_COUNTS.PASSED_COUNT));
        final Field<BigDecimal> totalSum = DSL.sum(perRun.field(RUN_PASS_COUNTS.TOTAL_COUNT));
        final Table<?> perSuiteWeek = dsl.select(
                        perRun.field(RUN_PASS_COUNTS.TEST_SUITE_ID),
                        weekIndex.as(WEEK_INDEX),
                        passedSum.as(PASSED_SUM),
                        totalSum.as(TOTAL_SUM))
                .from(perRun)
                .where(perRun.field(COMPUTATION_RANK, Integer.class).eq(1))
                .groupBy(perRun.field(RUN_PASS_COUNTS.TEST_SUITE_ID), weekIndex)
                .having(totalSum.gt(BigDecimal.ZERO))
                .asTable(PER_SUITE_WEEK);

        final Field<Long> week = perSuiteWeek.field(WEEK_INDEX, Long.class);
        final Field<Double> rate = perSuiteWeek
                .field(PASSED_SUM, BigDecimal.class)
                .cast(SQLDataType.DOUBLE)
                .div(perSuiteWeek.field(TOTAL_SUM, BigDecimal.class).cast(SQLDataType.DOUBLE));
        return dsl.select(
                        week,
                        DSL.count().filterWhere(rate.ge(threshold)).as(HEALTHY_COUNT),
                        DSL.count().filterWhere(rate.lt(threshold)).as(FAILING_COUNT))
                .from(perSuiteWeek)
                .groupBy(week)
                .orderBy(week)
                .fetch(r -> new WeeklySuiteHealth(
                        r.get(week).intValue(),
                        r.get(HEALTHY_COUNT, Integer.class),
                        r.get(FAILING_COUNT, Integer.class)));
    }

    /**
     * Tombstone-filtered rows of the scope, each with its rank among its run's computations (see
     * {@link #computationOrder}). Callers keep rank 1 to reduce each run to its latest computation.
     */
    private Table<?> computationRankedPerRun(Condition scope) {
        final List<SelectField<?>> perRunColumns = new ArrayList<>(List.of(RUN_PASS_COUNTS.fields()));
        perRunColumns.add(DSL.rowNumber()
                .over(DSL.partitionBy(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID).orderBy(computationOrder()))
                .as(COMPUTATION_RANK));
        return dsl.select(perRunColumns)
                .from(RUN_PASS_COUNTS)
                .where(scope)
                .and(RunDeletionFilter.notDeleted(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID))
                .asTable(PER_RUN);
    }

    private static List<SelectField<?>> projection(Table<?> ranked) {
        final List<SelectField<?>> fields = new ArrayList<>();
        for (final Field<?> f : RUN_PASS_COUNTS.fields()) {
            fields.add(ranked.field(f));
        }
        return fields;
    }
}
