-- Runs that ended while still PENDING (restart reconciliation, snapshot or DIAL route-trigger failure,
-- cancel while PENDING) were stored with completed_at_ms but no started_at_ms, because it used to be written
-- only on the PENDING -> RUNNING transition. They never ran, so their start equals their end (zero duration),
-- matching what the repository now writes for such runs.
UPDATE test_suite_runs
SET started_at_ms = completed_at_ms
WHERE started_at_ms IS NULL
  AND completed_at_ms IS NOT NULL
  AND status IN ('FAILED', 'CANCELLED');
