-- Wrap existing request_template.body and endpoint_ref.requestBodySchema JSONB values
-- in polymorphic wrappers with contentType discriminator.
-- Also wraps test_cases.request_template_override.body for non-null overrides.

-- 1. Wrap test_suites.request_template -> body
UPDATE test_suites
SET request_template = jsonb_set(
    request_template,
    '{body}',
    jsonb_build_object('contentType', 'application/json', 'content', request_template -> 'body')
)
WHERE request_template IS NOT NULL
  AND request_template -> 'body' IS NOT NULL
  AND jsonb_typeof(request_template -> 'body') != 'null'
  AND NOT (request_template -> 'body' ? 'contentType');

-- 2. Wrap test_suites.endpoint_ref -> requestBodySchema
UPDATE test_suites
SET endpoint_ref = jsonb_set(
    endpoint_ref,
    '{requestBodySchema}',
    jsonb_build_object('contentType', 'application/json', 'schema', endpoint_ref -> 'requestBodySchema')
)
WHERE endpoint_ref IS NOT NULL
  AND endpoint_ref -> 'requestBodySchema' IS NOT NULL
  AND jsonb_typeof(endpoint_ref -> 'requestBodySchema') != 'null'
  AND NOT (endpoint_ref -> 'requestBodySchema' ? 'contentType');

-- 3. Wrap test_cases.request_template_override -> body
UPDATE test_cases
SET request_template_override = jsonb_set(
    request_template_override,
    '{body}',
    jsonb_build_object('contentType', 'application/json', 'content', request_template_override -> 'body')
)
WHERE request_template_override IS NOT NULL
  AND request_template_override -> 'body' IS NOT NULL
  AND jsonb_typeof(request_template_override -> 'body') != 'null'
  AND NOT (request_template_override -> 'body' ? 'contentType');
