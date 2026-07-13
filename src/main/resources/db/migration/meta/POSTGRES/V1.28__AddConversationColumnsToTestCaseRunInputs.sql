-- Row-based multi-turn: the snapshot assembles each conversation into ONE input row
-- (one execution unit). Single-turn units keep using the existing scalar columns
-- (test_case_id / test_case_name / test_case_data); multi-turn units additionally carry
-- the ordered turns in `turns`. Broken conversations are marked so the executor emits a
-- single 0/0 ERROR result row without invoking the model. All columns are additive and
-- nullable (except the defaulted marker), so the single-turn path is unchanged.
ALTER TABLE test_case_run_inputs
    ADD COLUMN conversation_id VARCHAR(36),
    ADD COLUMN total_turns     INTEGER,
    ADD COLUMN turns           JSONB,
    ADD COLUMN broken          BOOLEAN NOT NULL DEFAULT false;
