-- Request Template Restructure
--
-- Breaking change: drops all existing test suite and test case data.
-- No data migration — this is acceptable as there are no production clients.
--
-- test_suites: replace test_cases_definition with test_case_schema, request_template,
--   input_bindings; add is_valid + validation_warnings for suite-level soft validation.
-- test_cases: replace parameters + facts with unified data map;
--   add request_template_override + input_bindings_override for per-case overrides.

-- 1. Drop all existing data (CASCADE removes test_cases and revalidation_tasks via FK)
TRUNCATE TABLE test_suites CASCADE;

-- 2. Restructure test_suites
ALTER TABLE test_suites
    DROP COLUMN IF EXISTS test_cases_definition;

ALTER TABLE test_suites
    ADD COLUMN test_case_schema JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN request_template JSONB,
    ADD COLUMN input_bindings JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN is_valid BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN validation_warnings JSONB NOT NULL DEFAULT '[]'::jsonb;

-- 3. Restructure test_cases: drop old columns, add new ones
-- Drop GIN indexes on columns being removed
DROP INDEX IF EXISTS idx_test_cases_parameters;
DROP INDEX IF EXISTS idx_test_cases_facts;

ALTER TABLE test_cases
    DROP COLUMN IF EXISTS parameters,
    DROP COLUMN IF EXISTS facts;

ALTER TABLE test_cases
    ADD COLUMN data JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN request_template_override JSONB,
    ADD COLUMN input_bindings_override JSONB;
