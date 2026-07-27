-- Multi-request test suites: a suite may issue an ordered CHAIN of independent HTTP requests
-- against the same deployment, instead of exactly one. The chain is stored ASYMMETRICALLY:
-- request 0 stays in the pre-existing flat columns (endpoint_ref, request_template,
-- input_bindings, response_columns) and additional_requests holds elements 1..N-1 in order.
-- The discriminator is simply "additional_requests is non-empty" — there is no separate flag.
-- NULL or [] denotes a single-request suite (all existing rows, backward compatible), so no
-- migration of existing data is needed.
--
-- Each additional_requests element is a complete request spec:
--   {type, label, endpointRef, requestTemplate, inputBindings, responseColumns}
-- `type` is a Jackson discriminator (HTTP | MCP_TOOL); MCP_TOOL is rejected at suite save.
-- deployment_ref stays suite-level: every request in the chain targets the same deployment.
--
-- request_label names request 0 (the flat request). Labels are optional everywhere; the chain
-- normalizer defaults an absent label to `request-{n}` (1-based), so every normalized request
-- has exactly one non-null, chain-unique label.
ALTER TABLE test_suites
    ADD COLUMN IF NOT EXISTS additional_requests JSONB,
    ADD COLUMN IF NOT EXISTS request_label       VARCHAR(255);
