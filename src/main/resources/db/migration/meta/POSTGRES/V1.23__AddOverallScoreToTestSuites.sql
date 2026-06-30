-- Per-suite "overall" metric-score definition. NULL means "use the system default" (a Java constant:
-- mean of the run's metric averages, computed only when the run has exactly one numeric metric field).
-- A non-null value is a custom, self-contained StructuredQuery expression authored for this suite.
-- Captured verbatim into the run's suite_snapshot; Phase-3 computation reads it from the snapshot.
ALTER TABLE test_suites ADD COLUMN overall_score JSONB;
