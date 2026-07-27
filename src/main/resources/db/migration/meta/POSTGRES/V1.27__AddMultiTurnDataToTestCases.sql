-- Array-based multi-turn: a multi-turn test case is a SINGLE row carrying an ordered
-- array of turn data maps in multi_turn_data. Each element carries that turn's PER-TURN
-- fields (dataset schema fields marked perTurn=true); the row's `data` map carries the
-- case's SHARED (test-case-level) fields — constant across turns. The two coexist; the
-- multi-turn discriminator is simply multi_turn_data IS NOT NULL. The column is nullable;
-- NULL denotes a single-turn test case (all existing rows, backward compatible). No
-- migration of existing data is needed. Field scope lives in datasets.test_case_schema
-- (FieldDefinitionDto.perTurn), so no per-field DB column is required.
ALTER TABLE test_cases
    ADD COLUMN IF NOT EXISTS multi_turn_data JSONB;
