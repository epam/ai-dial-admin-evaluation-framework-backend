-- Drop streaming timing columns (TTFT/TTLT) and add retry tracking columns
ALTER TABLE test_case_run_results DROP COLUMN IF EXISTS time_to_first_token_ms;
ALTER TABLE test_case_run_results DROP COLUMN IF EXISTS time_to_last_token_ms;

ALTER TABLE test_case_run_results ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE test_case_run_results ADD COLUMN log_details JSONB;
