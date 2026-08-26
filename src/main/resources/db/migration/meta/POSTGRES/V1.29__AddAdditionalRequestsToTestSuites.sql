-- Request chain: request #0 stays the suite's own endpoint_ref/request_template/response_columns/
-- input_bindings columns; additional_requests holds requests 1..N as an ordered JSONB array of
-- RequestDefinitionDto. Defaults to '[]' so every existing suite behaves as a one-element chain.
-- request_name labels request #0, mirroring RequestDefinitionDto.name on each additional request.
ALTER TABLE test_suites
    ADD COLUMN additional_requests JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN request_name VARCHAR(255);
