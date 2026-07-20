-- Row-based multi-turn: a multi-turn is an ordered group of test-case rows,
-- keyed by multi_turn_id and ordered by turn_index. Both columns are nullable;
-- NULL/NULL denotes a single-turn test case (all existing rows, backward compatible).
ALTER TABLE test_cases
    ADD COLUMN IF NOT EXISTS multi_turn_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS turn_index      INTEGER;

-- Grouping/lookup of a multi-turn's turns within a dataset.
CREATE INDEX idx_test_cases_multi_turn ON test_cases (dataset_id, multi_turn_id);

-- Defensive per-turn uniqueness: no two rows may share the same turn index within a
-- multi-turn. Single-turn rows (multi_turn_id IS NULL) are excluded and keep the
-- existing name-based uniqueness.
CREATE UNIQUE INDEX uq_test_cases_multi_turn_turn
    ON test_cases (dataset_id, multi_turn_id, turn_index)
    WHERE multi_turn_id IS NOT NULL;
