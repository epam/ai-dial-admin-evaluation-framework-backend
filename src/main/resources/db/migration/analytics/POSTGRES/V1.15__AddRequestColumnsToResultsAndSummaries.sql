-- Per-request result rows: each request of a multi-request suite's chain is its own row.
-- request_index is the 0-based chain position (matches run_index/turn_index conventions);
-- request_label is that request's resolved human-readable label, denormalized onto the row so
-- analytics consumers never need a cross-datasource lookup into the meta DB to render it.
--
-- There is deliberately NO total_requests column, unlike turn_index/total_turns: turn count is
-- DATA-dependent (varies per test case, so a condition author cannot know N), whereas request
-- count is CONFIG-dependent — fixed for the run, identical on every row, and derivable from the
-- run's frozen snapshot. An aborted (fail-fast) chain is identified by its final row's ERROR
-- status, so truncation detection needs no integer either.
--
-- NOT NULL DEFAULT backfills existing rows to request_index = 0 in a single metadata-only
-- statement (PG 11+, no table rewrite). request_label stays nullable: pre-existing rows and
-- imported external-run rows may have none.
ALTER TABLE test_case_run_results
    ADD COLUMN IF NOT EXISTS request_index INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS request_label VARCHAR(255);

ALTER TABLE test_case_eval_summaries
    ADD COLUMN IF NOT EXISTS request_index INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS request_label VARCHAR(255);

-- Extend both idempotency keys with request_index so each chain request is uniquely keyed and
-- ON CONFLICT writes hold per request. Columns are NOT NULL, so plain unique constraints/indexes
-- suffice (no NULLS NOT DISTINCT). request_label deliberately does NOT participate: it is a
-- mutable display value carrying no additional uniqueness, following the existing convention
-- where test_case_name sits beside the keyed test_case_id.
-- NOTE: on a large analytics deployment, replace the drop/re-add below with a non-transactional
-- migration using CREATE UNIQUE INDEX CONCURRENTLY + constraint swap to avoid a long exclusive
-- lock (same caveat as V1.13/V1.14).
ALTER TABLE test_case_run_results
    DROP CONSTRAINT uq_results_run_case_index;

ALTER TABLE test_case_run_results
    ADD CONSTRAINT uq_results_run_case_index
        UNIQUE (test_suite_run_id, test_case_id, run_index, turn_index, request_index, created_at_ms);

DROP INDEX uq_eval_summaries_natural_key;

CREATE UNIQUE INDEX uq_eval_summaries_natural_key
    ON test_case_eval_summaries (
        test_suite_run_id, test_case_id, run_index, turn_index, request_index, computation_id, created_at_ms);
