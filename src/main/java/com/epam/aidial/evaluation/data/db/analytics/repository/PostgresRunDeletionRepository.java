package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.RUN_DELETIONS;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link RunDeletionRepository}.
 * Writes tombstone rows to mark analytics data as excluded from read paths.
 */
@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresRunDeletionRepository implements RunDeletionRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    /**
     * Idempotently inserts a tombstone row for a deleted run.
     * Uses INSERT ... ON CONFLICT (test_suite_run_id) DO NOTHING to silently skip
     * if the run has already been tombstoned (e.g., retried delete).
     */
    @Override
    public void insert(UUID testSuiteRunId, long deletedAtMs) {
        dsl.insertInto(RUN_DELETIONS)
                .set(RUN_DELETIONS.TEST_SUITE_RUN_ID, testSuiteRunId.toString())
                .set(RUN_DELETIONS.DELETED_AT_MS, deletedAtMs)
                .onConflict(RUN_DELETIONS.TEST_SUITE_RUN_ID)
                .doNothing()
                .execute();
        log.debug("Inserted or skipped deletion tombstone for run {}", testSuiteRunId);
    }
}
