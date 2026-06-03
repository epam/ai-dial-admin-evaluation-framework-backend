-- Early-stage project: duplicate rows (case-insensitive) are pruned below.
-- Uniqueness is enforced via functional indexes on LOWER(name) / LOWER(test_case_name).
-- Oldest row per key is kept (MIN(created_at_ms)).

-- Prune duplicate test_cases: keep one row per (test_suite_id, LOWER(test_case_name)), oldest wins.
DELETE FROM test_cases
WHERE id NOT IN (
    SELECT id
    FROM (
        SELECT DISTINCT ON (test_suite_id, LOWER(test_case_name)) id
        FROM test_cases
        ORDER BY test_suite_id, LOWER(test_case_name), created_at_ms ASC
    ) keep
);

-- Prune duplicate test_suites: keep one row per LOWER(name), oldest wins.
DELETE FROM test_suites
WHERE id NOT IN (
    SELECT id
    FROM (
        SELECT DISTINCT ON (LOWER(name)) id
        FROM test_suites
        ORDER BY LOWER(name), created_at_ms ASC
    ) keep
);

-- Drop non-unique index on name (replaced by unique functional index below).
DROP INDEX IF EXISTS idx_test_suites_name;

-- Case-insensitive unique index on test_suites.name
CREATE UNIQUE INDEX uq_test_suites_name ON test_suites (LOWER(name));

-- Case-insensitive unique composite index on test_cases (test_suite_id, test_case_name)
CREATE UNIQUE INDEX uq_test_cases_suite_name ON test_cases (test_suite_id, LOWER(test_case_name));
