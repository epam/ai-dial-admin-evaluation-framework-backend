-- Optional per-suite "overall" metric-score definition used for PER-TEST-CASE scoring
-- (test_case_eval_scores.score/.passed) instead of overall_score. NULL means per-test-case scoring
-- falls back to overall_score (today's behavior). overall_score itself is unaffected: it still
-- drives the run-level "overall" aggregate (Phase 3) unconditionally. Captured verbatim into the
-- run's suite_snapshot alongside overall_score.
ALTER TABLE test_suites ADD COLUMN test_case_overall_score JSONB;
