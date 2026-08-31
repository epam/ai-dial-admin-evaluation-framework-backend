package com.epam.aidial.evaluation.configuration.datasource;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Resourceless {@link org.springframework.transaction.PlatformTransactionManager} for the analytics
 * datasource when {@code datasource.analytics.vendor=CLICKHOUSE}.
 *
 * <p>ClickHouse has no transactions (no BEGIN/COMMIT/ROLLBACK semantics on a single connection), so
 * there is nothing for a real transaction manager to demarcate. Analytics writes on this vendor are
 * idempotent, append-only batches deduplicated at read time by {@code ReplacingMergeTree} (see
 * {@code db/migration/analytics/CLICKHOUSE/V1.1__Init.sql}), so a failed batch can simply be retried
 * without a rollback boundary.
 *
 * <p>This manager exists so that existing {@code @Transactional("analyticsTransactionManager")}
 * demarcations and {@code TransactionTemplate} callers throughout the analytics service layer
 * continue to bind to a valid bean and execute without error — they just perform no actual
 * transactional work when running against ClickHouse.
 */
public class ClickHouseNoOpTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // No-op: ClickHouse has no transaction to begin.
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        // No-op: ClickHouse has no transaction to commit.
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        // No-op: ClickHouse has no transaction to roll back.
    }
}
