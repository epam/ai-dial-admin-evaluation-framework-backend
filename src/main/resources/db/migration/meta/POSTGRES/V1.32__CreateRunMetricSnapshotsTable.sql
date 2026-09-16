-- Relocate run_metric_snapshots from the analytics database into meta.
-- The column set mirrors analytics V1.6__CreateRunMetricSnapshotsTable.sql exactly and adds a
-- cascading FK to test_suite_runs, which no cross-database constraint could previously provide:
-- deleting a run used to orphan its snapshot rows silently.
--
-- Companion Java migration: V1_33__CopyRunMetricSnapshotsFromAnalytics copies historical rows from
-- the analytics database into this table. It is a Flyway BaseJavaMigration registered explicitly
-- via .javaMigrations(...) in MetaFlywayConfiguration (it needs the analytics DataSource injected),
-- so it lives in the Java sources and is NOT discoverable in this directory. All DDL stays here in
-- SQL: generateJooq and JooqSchemaDriftTest build their own Flyway from these files and never see a
-- programmatically registered migration.
--
-- The analytics copy of this table is deliberately kept, frozen and unread; no analytics migration
-- ships with this change. Any future drop of it MUST be ordered after meta V1.33.
CREATE TABLE run_metric_snapshots (
    id                             VARCHAR(36)  NOT NULL PRIMARY KEY,
    computation_id                 VARCHAR(36)  NOT NULL,
    test_suite_run_id              VARCHAR(36)  NOT NULL,
    tsmd_id                        VARCHAR(36)  NOT NULL,
    tsmd_name                      VARCHAR(255) NOT NULL,
    metric_declaration_id          VARCHAR(36)  NOT NULL,
    metric_declaration_version_id  VARCHAR(36)  NOT NULL,
    config_bindings                JSONB        NOT NULL DEFAULT '[]',
    input_bindings                 JSONB        NOT NULL DEFAULT '[]',
    output_schema                  JSONB        NOT NULL DEFAULT '{}',
    computed_at_ms                 BIGINT       NOT NULL,
    CONSTRAINT fk_run_metric_snapshots_run FOREIGN KEY (test_suite_run_id)
        REFERENCES test_suite_runs (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_run_metric_snapshots_computation_tsmd
    ON run_metric_snapshots (computation_id, tsmd_id);

CREATE INDEX idx_run_metric_snapshots_run
    ON run_metric_snapshots (test_suite_run_id);
