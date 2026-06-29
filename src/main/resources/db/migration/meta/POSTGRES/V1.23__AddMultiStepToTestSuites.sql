ALTER TABLE test_suites
    ADD COLUMN multi_step BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN multistep_input_bindings JSONB;
