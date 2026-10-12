package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.RUN_DELETIONS;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * The single definition of "this analytics row belongs to a live (non-deleted) run": an anti-join against
 * the {@code run_deletions} tombstone table, a primary-key point lookup per row. Every read of a run-scoped
 * analytics table — direct repository reads and Query DSL entity resolvers alike — ANDs this condition
 * onto its own filter; writes never do.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RunDeletionFilter {

    /** Condition that holds when the run identified by {@code runIdColumn} has no tombstone. */
    public static Condition notDeleted(Field<String> runIdColumn) {
        return DSL.notExists(
                DSL.selectOne().from(RUN_DELETIONS).where(RUN_DELETIONS.TEST_SUITE_RUN_ID.eq(runIdColumn)));
    }
}
