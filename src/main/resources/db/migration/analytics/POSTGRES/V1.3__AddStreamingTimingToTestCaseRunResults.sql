-- Add streaming timing metrics to test_case_run_results
-- time_to_first_token_ms: elapsed time from request start to first content delta (streaming) or exec_duration_ms (non-streaming)
-- time_to_last_token_ms: elapsed time from request start to last content delta (streaming) or exec_duration_ms (non-streaming)
-- Nullable for backward compatibility with existing rows; new results always populate both fields.

ALTER TABLE test_case_run_results
    ADD COLUMN time_to_first_token_ms BIGINT,
    ADD COLUMN time_to_last_token_ms BIGINT;
