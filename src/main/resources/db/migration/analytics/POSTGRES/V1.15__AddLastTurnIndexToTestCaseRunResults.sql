-- last_turn_index = the maximum authored turn_index among a multi-turn's surviving turns.
-- Used to evaluate turn position (turn.last) correctly once surviving turns may be non-contiguous
-- (a turn disabled or filtered out at the start/middle/end no longer breaks the multi-turn, so the
-- authored index of the last surviving turn is no longer count-1). Internal correctness column: not
-- exposed on response DTOs or the CSV export, and NOT part of any unique/idempotency key.
-- NOT NULL DEFAULT backfills existing rows to 0 in a single metadata-only statement (PG 11+, no rewrite);
-- single-turn rows read last_turn_index = 0 (turn.last = true), matching prior single-turn semantics.
ALTER TABLE test_case_run_results
    ADD COLUMN IF NOT EXISTS last_turn_index INTEGER NOT NULL DEFAULT 0;
