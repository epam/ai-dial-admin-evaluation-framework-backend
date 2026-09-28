-- Denormalizes run/test-case context onto test_case_eval_scores and re-keys it to one row per test
-- case, WITHOUT touching a single existing row: this table is already deployed with real, multi-turn
-- data whose duplicate rows (one per raw test_case_eval_summaries row) can genuinely disagree on score
-- (a stale value written in an earlier flush batch, never revisited by a later flush that only updates
-- its own batch's rows) and whose computed_at_ms can tie within a flush batch -- so a destructive
-- collapse of existing rows was investigated and rejected (see this change's design.md).
--
-- eval_summary_id becomes a nullable old/new-format discriminator instead of the primary key: every
-- legacy row keeps its real value; every row written from now on carries eval_summary_id = NULL and is
-- written once per (test_suite_run_id, test_case_id, computation_id), computed from the already
-- one-row-per-test-case test_case_metric_scores_aggregated rather than broadcast to every raw
-- test_case_eval_summaries row. A new surrogate id column is the primary key, and a partial unique
-- index enforces the one-row-per-test-case invariant only among new-format rows.
ALTER TABLE test_case_eval_scores
    ADD COLUMN test_suite_run_id VARCHAR(36),
    ADD COLUMN test_case_id      VARCHAR(36),
    ADD COLUMN test_case_name    VARCHAR(255),
    ADD COLUMN computation_id    VARCHAR(36),
    ADD COLUMN execution_status  VARCHAR(20);

UPDATE test_case_eval_scores tces
SET test_suite_run_id = tes.test_suite_run_id,
    test_case_id      = tes.test_case_id,
    test_case_name    = tes.test_case_name,
    computation_id    = tes.computation_id
FROM test_case_eval_summaries tes
WHERE tes.id = tces.eval_summary_id;

-- execution_status is a fresh per-test-case aggregate (bool_or across every run_index/request_index/
-- turn_index row of that test case for the computation -- see TestCaseExecutionStatusAggregator), never
-- copied from any single row's own execution_status.
UPDATE test_case_eval_scores tces
SET execution_status = agg.execution_status
FROM (
    SELECT test_suite_run_id, test_case_id, computation_id,
           CASE WHEN bool_or(execution_status <> 'SUCCESS') THEN 'FAILED' ELSE 'SUCCESS' END AS execution_status
    FROM test_case_eval_summaries
    GROUP BY test_suite_run_id, test_case_id, computation_id
) agg
WHERE tces.test_suite_run_id = agg.test_suite_run_id
  AND tces.test_case_id = agg.test_case_id
  AND tces.computation_id = agg.computation_id;

ALTER TABLE test_case_eval_scores
    ALTER COLUMN test_suite_run_id SET NOT NULL,
    ALTER COLUMN test_case_id SET NOT NULL,
    ALTER COLUMN test_case_name SET NOT NULL,
    ALTER COLUMN computation_id SET NOT NULL,
    ALTER COLUMN execution_status SET NOT NULL;

-- New surrogate PK: reuse each legacy row's eval_summary_id as its id (already unique per row, no new
-- UUID generation needed), then drop the old PK and free eval_summary_id to become nullable.
ALTER TABLE test_case_eval_scores ADD COLUMN id VARCHAR(36);
UPDATE test_case_eval_scores SET id = eval_summary_id;
ALTER TABLE test_case_eval_scores
    ALTER COLUMN id SET NOT NULL,
    DROP CONSTRAINT test_case_eval_scores_pkey,
    ALTER COLUMN eval_summary_id DROP NOT NULL,
    ADD PRIMARY KEY (id);

-- Enforces "one row per test case" only among new-format rows (eval_summary_id IS NULL); legacy rows
-- keep coexisting, however many there are per key, completely untouched.
CREATE UNIQUE INDEX uq_test_case_eval_scores_new_format_key
    ON test_case_eval_scores (test_suite_run_id, test_case_id, computation_id)
    WHERE eval_summary_id IS NULL;

-- Supports the test_case_eval_scores entity's SELECT DISTINCT ON (test_suite_run_id, test_case_id,
-- computation_id) ... ORDER BY ... access pattern: prefer a new-format row over any number of legacy
-- rows for the same key, then freshest computed_at_ms among same-format rows.
CREATE INDEX idx_test_case_eval_scores_natural_key
    ON test_case_eval_scores (test_suite_run_id, test_case_id, computation_id,
                               (eval_summary_id IS NULL) DESC, computed_at_ms DESC);
