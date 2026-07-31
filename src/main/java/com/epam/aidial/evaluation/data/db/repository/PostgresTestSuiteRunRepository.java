package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Sequences.TEST_SUITE_RUN_NAME_SEQ;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_RUNS;

import com.epam.aidial.evaluation.data.db.mapper.TestSuiteRunRecordMapper;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.OrderByBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.PageRequestSqlBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.SortWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SortField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestSuiteRunRepository implements TestSuiteRunRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final TestSuiteRunRecordMapper recordMapper;
    private final TransactionTimestampContext transactionTimestampContext;
    private final WhereBuilder whereBuilder;
    private final OrderByBuilder orderByBuilder;

    // List queries exclude suite_snapshot to avoid TOAST decompression on bulk queries
    private static final Field<?>[] SELECT_LIST_FIELDS = {
        TEST_SUITE_RUNS.ID,
        TEST_SUITE_RUNS.TEST_SUITE_ID,
        TEST_SUITE_RUNS.TEST_RUN_NAME,
        TEST_SUITE_RUNS.STATUS,
        TEST_SUITE_RUNS.RUN_CONFIG,
        TEST_SUITE_RUNS.NUMBER_OF_TEST_CASES,
        TEST_SUITE_RUNS.STARTED_AT_MS,
        TEST_SUITE_RUNS.COMPLETED_AT_MS,
        TEST_SUITE_RUNS.ERROR_MESSAGE,
        TEST_SUITE_RUNS.ERROR_DETAILS,
        TEST_SUITE_RUNS.CREATED_AT_MS,
        TEST_SUITE_RUNS.UPDATED_AT_MS
    };

    @Override
    public TestSuiteRun save(TestSuiteRun run) {
        long now = transactionTimestampContext.getTimestamp();
        if (run.getId() == null) {
            run.setId(UUID.randomUUID());
        }
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        dsl.insertInto(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.ID, run.getId().toString())
                .set(TEST_SUITE_RUNS.TEST_SUITE_ID, run.getTestSuiteId().toString())
                .set(TEST_SUITE_RUNS.TEST_RUN_NAME, run.getTestRunName())
                .set(TEST_SUITE_RUNS.STATUS, run.getStatus())
                .set(TEST_SUITE_RUNS.RUN_CONFIG, toJsonb(run.getRunConfig()))
                .set(TEST_SUITE_RUNS.NUMBER_OF_TEST_CASES, run.getNumberOfTestCases())
                .set(TEST_SUITE_RUNS.STARTED_AT_MS, run.getStartedAt())
                .set(TEST_SUITE_RUNS.COMPLETED_AT_MS, run.getCompletedAt())
                .set(TEST_SUITE_RUNS.ERROR_MESSAGE, run.getErrorMessage())
                .set(TEST_SUITE_RUNS.ERROR_DETAILS, toJsonb(run.getErrorDetails()))
                .set(TEST_SUITE_RUNS.SUITE_SNAPSHOT, toJsonb(run.getSuiteSnapshot()))
                .set(TEST_SUITE_RUNS.CREATED_AT_MS, run.getCreatedAt())
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, run.getUpdatedAt())
                .execute();
        log.debug("Created TestSuiteRun with id: {}", run.getId());
        return run;
    }

    @Override
    public Optional<TestSuiteRun> findById(UUID id) {
        return dsl.selectFrom(TEST_SUITE_RUNS)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public Optional<TestSuiteRun> findLatestByTestSuiteId(UUID testSuiteId) {
        // Full-row select (incl. suite_snapshot) so callers can derive run schema from the snapshot.
        return dsl.selectFrom(TEST_SUITE_RUNS)
                .where(TEST_SUITE_RUNS.TEST_SUITE_ID.eq(testSuiteId.toString()))
                .orderBy(TEST_SUITE_RUNS.CREATED_AT_MS.desc(), TEST_SUITE_RUNS.ID.desc())
                .limit(1)
                .fetchOptional(recordMapper::map);
    }

    @Override
    public Page<TestSuiteRun> findAll(
            PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }

        Condition condition =
                whereBuilder.build(filters != null ? filters : List.of(), FilterWhitelists.TEST_SUITE_RUNS);
        long totalCount = includeTotalCount ? count(condition) : -1;
        List<SortField<?>> orderBy = orderByBuilder.build(pageRequest.getSort(), SortWhitelists.TEST_SUITE_RUNS);

        int limit = PageRequestSqlBuilder.limit(pageRequest);
        long offset = PageRequestSqlBuilder.offset(pageRequest);

        List<TestSuiteRun> content = dsl.select(Arrays.asList(SELECT_LIST_FIELDS))
                .from(TEST_SUITE_RUNS)
                .where(condition)
                .orderBy(orderBy)
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::mapWithoutSnapshot);

        return includeTotalCount ? Page.of(content, pageRequest, totalCount) : Page.withoutTotal(content, pageRequest);
    }

    @Override
    public int countByStatuses(List<String> statuses) {
        Long count = dsl.selectCount()
                .from(TEST_SUITE_RUNS)
                .where(TEST_SUITE_RUNS.STATUS.in(statuses))
                .fetchOne(0, Long.class);
        return count != null ? count.intValue() : 0;
    }

    @Override
    public int countByTestSuiteIdAndStatuses(UUID testSuiteId, List<String> statuses) {
        Long count = dsl.selectCount()
                .from(TEST_SUITE_RUNS)
                .where(TEST_SUITE_RUNS
                        .TEST_SUITE_ID
                        .eq(testSuiteId.toString())
                        .and(TEST_SUITE_RUNS.STATUS.in(statuses)))
                .fetchOne(0, Long.class);
        return count != null ? count.intValue() : 0;
    }

    @Override
    public int updateStatusOptimistic(UUID id, String newStatus, String expectedStatus) {
        long now = transactionTimestampContext.getTimestamp();
        return dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.STATUS, newStatus)
                .set(TEST_SUITE_RUNS.COMPLETED_AT_MS, now)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, now)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()).and(TEST_SUITE_RUNS.STATUS.eq(expectedStatus)))
                .execute();
    }

    @Override
    public void updateToRunning(UUID id, long startedAt, long updatedAt) {
        dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.STATUS, "RUNNING")
                .set(TEST_SUITE_RUNS.STARTED_AT_MS, startedAt)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, updatedAt)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public void updateToCompleted(UUID id, long completedAt, long updatedAt) {
        dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.STATUS, "COMPLETED")
                .set(TEST_SUITE_RUNS.COMPLETED_AT_MS, completedAt)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, updatedAt)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public void updateToFailed(UUID id, String errorMessage, String errorDetails, long completedAt, long updatedAt) {
        dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.STATUS, "FAILED")
                .set(TEST_SUITE_RUNS.ERROR_MESSAGE, errorMessage)
                .set(TEST_SUITE_RUNS.ERROR_DETAILS, toJsonb(errorDetails))
                .set(TEST_SUITE_RUNS.COMPLETED_AT_MS, completedAt)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, updatedAt)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public void updateToCancelled(UUID id, long completedAt, long updatedAt) {
        dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.STATUS, "CANCELLED")
                .set(TEST_SUITE_RUNS.COMPLETED_AT_MS, completedAt)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, updatedAt)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public void updateSuiteSnapshot(UUID id, String snapshotJson, long updatedAt) {
        dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.SUITE_SNAPSHOT, toJsonb(snapshotJson))
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, updatedAt)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public void updateNumberOfTestCases(UUID id, int numberOfTestCases, long updatedAt) {
        dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.NUMBER_OF_TEST_CASES, numberOfTestCases)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, updatedAt)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public void updateTestRunName(UUID id, String newName) {
        long now = transactionTimestampContext.getTimestamp();
        dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.TEST_RUN_NAME, newName)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, now)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public boolean deleteById(UUID id) {
        int deleted = dsl.deleteFrom(TEST_SUITE_RUNS)
                .where(TEST_SUITE_RUNS.ID.eq(id.toString()))
                .execute();
        log.debug("Deleted TestSuiteRun with id: {}, rows affected: {}", id, deleted);
        return deleted > 0;
    }

    @Override
    public int failOrphanedRuns(
            List<String> orphanedStatuses, String failedStatus, String errorMessage, String errorDetails) {
        long now = transactionTimestampContext.getTimestamp();
        return dsl.update(TEST_SUITE_RUNS)
                .set(TEST_SUITE_RUNS.STATUS, failedStatus)
                .set(TEST_SUITE_RUNS.ERROR_MESSAGE, errorMessage)
                .set(TEST_SUITE_RUNS.ERROR_DETAILS, toJsonb(errorDetails))
                .set(TEST_SUITE_RUNS.COMPLETED_AT_MS, now)
                .set(TEST_SUITE_RUNS.UPDATED_AT_MS, now)
                .where(TEST_SUITE_RUNS.STATUS.in(orphanedStatuses))
                .execute();
    }

    @Override
    public long nextRunNameSequenceValue() {
        return dsl.nextval(TEST_SUITE_RUN_NAME_SEQ);
    }

    private long count(Condition condition) {
        Long count = dsl.selectCount().from(TEST_SUITE_RUNS).where(condition).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
