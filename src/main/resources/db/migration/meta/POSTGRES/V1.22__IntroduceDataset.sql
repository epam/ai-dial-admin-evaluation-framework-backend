-- Introduce Dataset entity
--
-- See openspec/changes/introduce-dataset-entity/{proposal,design,tasks}.md.
--
-- Centralizes test case shape (test_case_schema) and data (test_cases) under
-- a new datasets entity. Test suites become consumers via a mandatory
-- dataset_id FK; many suites may share one dataset.
--
-- Data backfill runs inline. The structural invariant for this migration is
-- dataset.id = source_suite.id for every pre-existing suite — that lets
-- test_cases.test_suite_id -> dataset_id be a pure metadata rename and lets
-- test_suites.dataset_id be populated via SET dataset_id = id.
--
-- Drops: test_suites.test_case_schema, test_cases.request_template_override,
-- test_cases.input_bindings_override, test_cases.is_enabled. The is_enabled
-- state is preserved by aggregating disabled case IDs into
-- test_suites.disabled_test_case_ids BEFORE the column is dropped.
--
-- In-flight revalidation_tasks rows (PENDING/RUNNING) are explicitly marked
-- FAILED with a structured error_message prefix so operators can distinguish
-- the migration-abort case from regular failures (no error_code column exists
-- on this table; the marker lives in the message).


------------------------------------------------------------------------------
-- 1. datasets table + uniqueness
------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS datasets (
    id                   VARCHAR(36) PRIMARY KEY,
    name                 VARCHAR(263) NOT NULL,
    description          VARCHAR(2000),
    test_case_schema     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    is_valid             BOOLEAN      NOT NULL DEFAULT TRUE,
    validation_warnings  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    visibility           VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE'
                                      CONSTRAINT ck_datasets_visibility
                                      CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_by           VARCHAR(255) NOT NULL,
    created_at_ms        BIGINT       NOT NULL,
    updated_at_ms        BIGINT       NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_datasets_name ON datasets (LOWER(name));
CREATE INDEX        IF NOT EXISTS idx_datasets_created_at_ms ON datasets (created_at_ms DESC);


------------------------------------------------------------------------------
-- 2. Backfill datasets from existing test_suites (1:1, same UUID)
------------------------------------------------------------------------------

INSERT INTO datasets (
    id,
    name,
    description,
    test_case_schema,
    is_valid,
    validation_warnings,
    version,
    created_by,
    created_at_ms,
    updated_at_ms
)
SELECT
    id,
    'DATASET_' || name,
    description,
    test_case_schema,
    TRUE,
    '[]'::jsonb,
    1,
    created_by,
    created_at_ms,
    updated_at_ms
FROM test_suites
ON CONFLICT (id) DO NOTHING;

-- All backfilled V1.22 rows take the DEFAULT 'PRIVATE'. Drop the default
-- so future application-issued INSERTs must supply visibility explicitly.
ALTER TABLE datasets
    ALTER COLUMN visibility DROP DEFAULT;


------------------------------------------------------------------------------
-- 3. test_suites: add dataset_id (FK to datasets), populate via self-reference
------------------------------------------------------------------------------

ALTER TABLE test_suites
    ADD COLUMN IF NOT EXISTS dataset_id VARCHAR(36);

UPDATE test_suites SET dataset_id = id WHERE dataset_id IS NULL;

-- dataset_id is NULLABLE: a suite may exist in an unbound state per the
-- add-dataset-visibility change. The binding-uniqueness invariant for
-- PRIVATE datasets is enforced by the trigger added later in this migration.
ALTER TABLE test_suites
    ADD CONSTRAINT fk_test_suites_dataset_id
    FOREIGN KEY (dataset_id) REFERENCES datasets (id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_test_suites_dataset_id ON test_suites (dataset_id);


------------------------------------------------------------------------------
-- 4. test_suites.disabled_test_case_ids: backfill from is_enabled=false cases
--    (must run BEFORE test_cases.is_enabled is dropped)
------------------------------------------------------------------------------

ALTER TABLE test_suites
    ADD COLUMN IF NOT EXISTS disabled_test_case_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE test_suites ts
SET disabled_test_case_ids = COALESCE(sub.ids, '[]'::jsonb)
FROM (
    SELECT test_suite_id AS suite_id,
           jsonb_agg(id::text) AS ids
    FROM test_cases
    WHERE is_enabled = FALSE
    GROUP BY test_suite_id
) sub
WHERE ts.id = sub.suite_id;


------------------------------------------------------------------------------
-- 5. Diagnostic: warn if any suite's disabled list exceeds 10000 entries
--    (per ValidationConstants.MAX_DISABLED_TC_IDS; not a hard fail)
------------------------------------------------------------------------------

DO $$
DECLARE
    over_cap_row RECORD;
BEGIN
    FOR over_cap_row IN
        SELECT id, name, jsonb_array_length(disabled_test_case_ids) AS disabled_count
        FROM test_suites
        WHERE jsonb_array_length(disabled_test_case_ids) > 10000
    LOOP
        RAISE NOTICE
            'introduce-dataset-entity: suite % (name=%) has % disabled test cases — exceeds MAX_DISABLED_TC_IDS=10000; next PUT will fail validation until operator truncates the list',
            over_cap_row.id, over_cap_row.name, over_cap_row.disabled_count;
    END LOOP;
END $$;


------------------------------------------------------------------------------
-- 6. test_suites: drop test_case_schema (now lives on datasets)
------------------------------------------------------------------------------

ALTER TABLE test_suites
    DROP COLUMN IF EXISTS test_case_schema;


------------------------------------------------------------------------------
-- 7. Diagnostic: list test_cases rows that have non-null override values
--    that are about to be discarded (acknowledged data loss per design D3)
------------------------------------------------------------------------------

DO $$
DECLARE
    override_row RECORD;
BEGIN
    FOR override_row IN
        SELECT id, test_suite_id, test_case_name,
               (request_template_override IS NOT NULL) AS has_request_override,
               (input_bindings_override   IS NOT NULL) AS has_input_override
        FROM test_cases
        WHERE request_template_override IS NOT NULL
           OR input_bindings_override   IS NOT NULL
    LOOP
        RAISE NOTICE
            'introduce-dataset-entity: dropping override columns for test_case id=% (suite=%, name=%) — request_template_override=%, input_bindings_override=%',
            override_row.id,
            override_row.test_suite_id,
            override_row.test_case_name,
            override_row.has_request_override,
            override_row.has_input_override;
    END LOOP;
END $$;


------------------------------------------------------------------------------
-- 8. test_cases: rebind FK from test_suites -> datasets (dataset_id = old
--    test_suite_id, by D1 invariant), swap uniqueness/indexes,
--    drop override + is_enabled columns
------------------------------------------------------------------------------

-- Drop the auto-named FK from V1.2 (test_cases.test_suite_id -> test_suites.id).
ALTER TABLE test_cases
    DROP CONSTRAINT IF EXISTS test_cases_test_suite_id_fkey;

-- Drop old indexes (uq_test_cases_suite_name created in V1.4; idx_test_cases_test_suite_id in V1.2).
DROP INDEX IF EXISTS uq_test_cases_suite_name;
DROP INDEX IF EXISTS idx_test_cases_test_suite_id;

-- Pure metadata rename (dataset.id == source_suite.id per D1, no row updates).
ALTER TABLE test_cases
    RENAME COLUMN test_suite_id TO dataset_id;

ALTER TABLE test_cases
    ADD CONSTRAINT fk_test_cases_dataset_id
    FOREIGN KEY (dataset_id) REFERENCES datasets (id) ON DELETE CASCADE;

CREATE INDEX        IF NOT EXISTS idx_test_cases_dataset_id ON test_cases (dataset_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_test_cases_dataset_name
    ON test_cases (dataset_id, LOWER(test_case_name));

-- Drop columns now that disabled state has been migrated (step 4) and
-- override diagnostic has fired (step 7).
ALTER TABLE test_cases
    DROP COLUMN IF EXISTS request_template_override,
    DROP COLUMN IF EXISTS input_bindings_override,
    DROP COLUMN IF EXISTS is_enabled;


------------------------------------------------------------------------------
-- 9. revalidation_tasks: abort in-flight tasks BEFORE retargeting the FK so
--    operators see a clean state and no v1-semantics task picks up after
--    the schema rebind. error_code column does not exist on this table;
--    the abort marker lives in error_message via a literal prefix.
------------------------------------------------------------------------------

UPDATE revalidation_tasks
SET status = 'FAILED',
    error_message = 'Aborted by introduce-dataset-entity migration: task suite has been re-rooted as a dataset; resubmit the schema change against the new dataset endpoint.',
    completed_at_ms = (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT
WHERE status IN ('PENDING', 'RUNNING');


------------------------------------------------------------------------------
-- 10. revalidation_tasks: retarget FK from test_suites -> datasets via
--     metadata-only rename (UUIDs unchanged by D1 invariant)
------------------------------------------------------------------------------

ALTER TABLE revalidation_tasks
    DROP CONSTRAINT IF EXISTS revalidation_tasks_test_suite_id_fkey;

DROP INDEX IF EXISTS idx_revalidation_tasks_test_suite_id;

ALTER TABLE revalidation_tasks
    RENAME COLUMN test_suite_id TO dataset_id;

ALTER TABLE revalidation_tasks
    ADD CONSTRAINT fk_revalidation_tasks_dataset_id
    FOREIGN KEY (dataset_id) REFERENCES datasets (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_revalidation_tasks_dataset_id ON revalidation_tasks (dataset_id);


------------------------------------------------------------------------------
-- 11. Backfill snapshotVersion and datasetRef in stored suite snapshots
--
-- Pre-V1.22 suite_snapshot JSONB blobs were produced by a SuiteSnapshotDto
-- whose Lombok @Builder.Default declared snapshotVersion = "1". Jackson
-- serializes that default unconditionally, so every legacy blob carries
-- "snapshotVersion": "1" explicitly in JSON and lacks the datasetRef key
-- that the v2 model introduces. Under post-V1.22 application code,
-- resolveSnapshot rejects any non-"2" version via
-- UnsupportedSnapshotVersionException — which would make every historical
-- run unexecutable after this migration is applied.
--
-- This step normalizes those rows in place: snapshotVersion is rewritten
-- to "2" and datasetRef is synthesized from the joined test_suites row.
-- The mapping is deterministic by D1 of introduce-dataset-entity
-- (dataset.id = source_suite.id) and CASCADE-safe (test_suite_runs.
-- test_suite_id has ON DELETE CASCADE — no orphan run rows survive to
-- hit a missing suite). The guard `(suite_snapshot -> 'datasetRef') IS NULL`
-- (semantically equivalent to "datasetRef key absent" for our data, where
-- no value is ever explicit JSON null) makes the UPDATE idempotent for
-- re-runs against partially-migrated data. We use the `->` IS NULL form
-- rather than the `?` key-existence operator so the SQL is portable across
-- JDBC execution paths — JDBC PreparedStatement interprets `?` as a
-- parameter placeholder, which collides with the Postgres operator.
------------------------------------------------------------------------------

-- BEGIN backfill suite_snapshot v2 shape
UPDATE test_suite_runs r
SET suite_snapshot = jsonb_set(
        jsonb_set(
            r.suite_snapshot,
            '{snapshotVersion}',
            '"2"'::jsonb,
            true),
        '{datasetRef}',
        jsonb_build_object(
            'id',      ts.id,
            'version', 1,
            'name',    'DATASET_' || ts.name),
        true)
FROM test_suites ts
WHERE r.test_suite_id = ts.id
  AND r.suite_snapshot IS NOT NULL
  AND (r.suite_snapshot -> 'datasetRef') IS NULL;
-- END backfill suite_snapshot v2 shape


------------------------------------------------------------------------------
-- 12. PRIVATE-dataset binding-uniqueness trigger
--
-- A PL/pgSQL constraint trigger that guarantees at most one suite is bound
-- to any PRIVATE dataset at a time. The invariant spans two tables
-- (datasets.visibility, test_suites.dataset_id) and cannot be expressed as
-- a partial unique index without denormalizing visibility onto test_suites.
--
-- Fires BEFORE INSERT OR UPDATE OF dataset_id on test_suites; early-returns
-- when NEW.dataset_id IS NULL so that unbind paths (rebind-to-null,
-- PRIVATE-delete cascade) are not blocked. Otherwise SELECT ... FOR UPDATE
-- the target datasets row (serializing concurrent binders) and rejects when
-- visibility='PRIVATE' AND another live suite already references the row.
--
-- On rejection the trigger raises ERRCODE='P0001' (PL/pgSQL raise_exception)
-- with MESSAGE TEXT 'PRIVATE_DATASET_ALREADY_BOUND'. The global
-- DefaultExceptionHandler inspects SQLException.getSQLState() and maps
-- 'P0001' to HTTP 409 with errorCode=PRIVATE_DATASET_ALREADY_BOUND. The
-- standard 23505 -> UNIQUE_CONSTRAINT_VIOLATION mapping is untouched.
------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_test_suites_private_binding_guard()
    RETURNS TRIGGER AS $$
DECLARE
    target_visibility VARCHAR(16);
    existing_count    INTEGER;
BEGIN
    IF NEW.dataset_id IS NULL THEN
        RETURN NEW;
    END IF;

    -- Lock the target dataset row so concurrent binders and visibility
    -- transitions serialize on the same lock.
    SELECT visibility
    INTO   target_visibility
    FROM   datasets
    WHERE  id = NEW.dataset_id
    FOR UPDATE;

    IF target_visibility IS NULL THEN
        -- FK will reject; do not duplicate the error here.
        RETURN NEW;
    END IF;

    IF target_visibility <> 'PRIVATE' THEN
        RETURN NEW;
    END IF;

    SELECT COUNT(*)
    INTO   existing_count
    FROM   test_suites
    WHERE  dataset_id = NEW.dataset_id
      AND  (TG_OP = 'INSERT' OR id <> NEW.id);

    IF existing_count > 0 THEN
        RAISE EXCEPTION 'PRIVATE_DATASET_ALREADY_BOUND'
            USING ERRCODE = 'P0001';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tg_test_suites_private_binding_guard ON test_suites;

CREATE TRIGGER tg_test_suites_private_binding_guard
    BEFORE INSERT OR UPDATE OF dataset_id ON test_suites
    FOR EACH ROW
    EXECUTE FUNCTION fn_test_suites_private_binding_guard();
