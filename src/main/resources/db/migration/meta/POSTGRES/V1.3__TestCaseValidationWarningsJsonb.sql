-- Change validation_warnings from TEXT[] to JSONB with structured warnings.
--
-- Existing data is pruned to empty array because:
-- 1. Early development phase, no external clients relying on this data
-- 2. Old format (string array) cannot be reliably converted to new structured format
--    (new format includes: source, path, property, message, code fields)
-- 3. TestCases will be revalidated when accessed or via bulk revalidation, repopulating warnings
--
-- The new structured format supports:
-- - source: PARAMETERS | FACTS (which part of test case failed)
-- - path: JSON path to the failing property
-- - property: leaf property name
-- - message: human-readable error message
-- - code: stable validation warning code (REQUIRED, TYPE, FORMAT, etc.)
ALTER TABLE test_cases
    ALTER COLUMN validation_warnings TYPE jsonb
    USING '[]'::jsonb;
