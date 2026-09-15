-- Replace the single-column run_metric_snapshots index with a composite one.
--
-- The "latest computation for a run" lookup (findLatestComputationId, and the correlated
-- metric_names subquery added for the test_suite_runs query entity) reads:
--   ORDER BY computed_at_ms DESC, computation_id DESC LIMIT 1
-- The old idx_run_metric_snapshots_run (test_suite_run_id) only narrows to the run's rows; Postgres
-- still had to sort them to satisfy ORDER BY. The new composite index's column order and directions
-- match that ORDER BY exactly, turning the lookup into an index-only range scan with no sort.
--
-- idx_run_metric_snapshots_run becomes a strict prefix of the new index (test_suite_run_id is the
-- leading column of both), so every existing WHERE test_suite_run_id = ? reader (findByRunId,
-- findByRunIdAndComputationId, findLatestComputationId, and the FK cascade on run delete) is already
-- served by the new index. The old index is now pure write cost and is dropped.
--
-- Flyway runs both statements in a single transaction, so the DROP INDEX below takes and holds an
-- ACCESS EXCLUSIVE lock (blocks readers too, not just writers) until the whole migration commits.
-- Acceptable at current run_metric_snapshots row counts; revisit (e.g. split into separate Flyway
-- versions, or CREATE/DROP INDEX CONCURRENTLY outside a transaction) if this table grows large enough
-- for the lock window to matter.
CREATE INDEX idx_run_metric_snapshots_run_computed_at
    ON run_metric_snapshots (test_suite_run_id, computed_at_ms DESC, computation_id DESC);

DROP INDEX idx_run_metric_snapshots_run;
