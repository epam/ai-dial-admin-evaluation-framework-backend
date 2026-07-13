-- Row-based multi-turn: a conversation is an ordered group of test-case rows,
-- keyed by conversation_id and ordered by turn_index. Both columns are nullable;
-- NULL/NULL denotes a single-turn test case (all existing rows, backward compatible).
ALTER TABLE test_cases
    ADD COLUMN conversation_id VARCHAR(36),
    ADD COLUMN turn_index      INTEGER;

-- Grouping/lookup of a conversation's turns within a dataset.
CREATE INDEX idx_test_cases_conversation ON test_cases (dataset_id, conversation_id);

-- Defensive per-turn uniqueness: no two rows may share the same turn index within a
-- conversation. Single-turn rows (conversation_id IS NULL) are excluded and keep the
-- existing name-based uniqueness.
CREATE UNIQUE INDEX uq_test_cases_conversation_turn
    ON test_cases (dataset_id, conversation_id, turn_index)
    WHERE conversation_id IS NOT NULL;
