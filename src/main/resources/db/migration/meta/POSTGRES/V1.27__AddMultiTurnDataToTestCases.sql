-- Array-based multi-turn: a multi-turn test case is a SINGLE row carrying an ordered
-- array of turn data maps in multi_turn_data (each element has the same shape as the
-- single-turn `data` map). The column is nullable; NULL denotes a single-turn test case
-- (all existing rows, backward compatible). No migration of existing data is needed.
ALTER TABLE test_cases
    ADD COLUMN IF NOT EXISTS multi_turn_data JSONB;

-- Mutual exclusivity: a row is either single-turn (`data` populated, multi_turn_data NULL)
-- or multi-turn (multi_turn_data non-null, `data` empty '{}'). Defense-in-depth — the app
-- never writes a state that violates this.
ALTER TABLE test_cases
    ADD CONSTRAINT chk_test_cases_multi_turn_exclusive
        CHECK (multi_turn_data IS NULL OR data = '{}'::jsonb);
