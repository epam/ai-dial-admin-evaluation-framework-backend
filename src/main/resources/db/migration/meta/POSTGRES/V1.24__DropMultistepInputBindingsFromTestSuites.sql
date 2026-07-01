-- Multi-turn conversations are now driven by array-valued test-case columns bound via the suite's
-- single input_bindings; per-turn suite-level bindings are removed. The multi_step flag is retained.
ALTER TABLE test_suites
    DROP COLUMN multistep_input_bindings;
