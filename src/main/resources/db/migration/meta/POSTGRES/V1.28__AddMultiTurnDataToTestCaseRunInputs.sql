-- Array-based multi-turn snapshot: the suite-run snapshot freezes a multi-turn case's
-- ordered turns into this nullable column (one input row per case, single-turn or
-- multi-turn). NULL denotes a single-turn input (the existing scalar `test_case_data`
-- path is unchanged). No `broken` marker / assembler — invalid and over-cap cases are
-- already is_valid=false and excluded by runnable selection.
ALTER TABLE test_case_run_inputs
    ADD COLUMN IF NOT EXISTS multi_turn_data JSONB;
