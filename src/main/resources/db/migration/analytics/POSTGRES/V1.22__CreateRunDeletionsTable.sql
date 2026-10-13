-- Soft-deletion tombstone table for run analytics exclusion.
-- When a test suite run is deleted, a row is inserted here to mark it as deleted.
-- Every read of a run-scoped analytics table anti-joins against this table on test_suite_run_id
-- (see RunDeletionFilter); no analytics row is ever updated, preserving the append-only invariant.
CREATE TABLE run_deletions (
    test_suite_run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    deleted_at_ms BIGINT NOT NULL
);
