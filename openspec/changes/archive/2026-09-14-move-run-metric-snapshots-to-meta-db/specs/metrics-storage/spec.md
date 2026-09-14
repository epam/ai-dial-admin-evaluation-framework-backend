## MODIFIED Requirements

### Requirement: Database schema for run metric snapshots
The **meta** database SHALL contain a `run_metric_snapshots` table storing per-computation binding and version snapshots. The table SHALL hold a foreign key to `test_suite_runs` so a run and its captured metric catalog live in one database and cannot diverge.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the meta Flyway migration V1.32 is applied
- **THEN** the `run_metric_snapshots` table SHALL have columns: `id` (VARCHAR(36), NOT NULL, PK), `computation_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `tsmd_id` (VARCHAR(36), NOT NULL), `tsmd_name` (VARCHAR(255), NOT NULL), `metric_declaration_id` (VARCHAR(36), NOT NULL), `metric_declaration_version_id` (VARCHAR(36), NOT NULL), `config_bindings` (JSONB, NOT NULL, DEFAULT '[]'), `input_bindings` (JSONB, NOT NULL, DEFAULT '[]'), `output_schema` (JSONB, NOT NULL, DEFAULT '{}'), `computed_at_ms` (BIGINT, NOT NULL)

#### Scenario: UNIQUE constraint
- **WHEN** the migration is applied
- **THEN** a UNIQUE index (`CREATE UNIQUE INDEX`, not a table constraint) SHALL exist on `(computation_id, tsmd_id)`

#### Scenario: Index for run lookup
- **WHEN** the migration is applied
- **THEN** an index SHALL exist on `(test_suite_run_id)` for listing snapshots by run

#### Scenario: Foreign key to test suite runs
- **WHEN** the migration is applied
- **THEN** `test_suite_run_id` SHALL carry a foreign key referencing `test_suite_runs(id)` with `ON DELETE CASCADE`

#### Scenario: Snapshot for an unknown run is rejected
- **WHEN** a snapshot row is written whose `test_suite_run_id` does not exist in `test_suite_runs`
- **THEN** the database SHALL reject the write, so orphaned snapshot rows cannot be created

#### Scenario: Analytics copy is no longer read
- **WHEN** any component reads run metric snapshots
- **THEN** it SHALL read the meta `run_metric_snapshots` table. The same-named table in the analytics database SHALL NOT be read or written by any code path.

### Requirement: Batch write run metric snapshots
The service SHALL support persisting run metric snapshots both via the external REST API (`POST /api/v1/run-metric-snapshots`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `RunMetricSnapshotService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`. The write SHALL execute in a meta-database transaction, so the run-existence check and the snapshot insert are atomic.
Status: **Implemented**

#### Scenario: Successful batch write
- **WHEN** client calls `POST /api/v1/run-metric-snapshots` with a valid envelope containing `testSuiteRunId`, `computationId`, `computedAtMs`, and `snapshots` array
- **THEN** system SHALL insert all snapshots atomically and return HTTP 201. The envelope's `computedAtMs` SHALL be applied to all inserted rows.

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the run from the meta database within the same transaction as the insert. If not found, return HTTP 404

#### Scenario: Internal write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine captures RunMetricSnapshots before evaluation
- **THEN** the `RunMetricSnapshotBatchWriteClient` SHALL convert internal models to `RunMetricSnapshotBatchWriteRequestDto` and delegate to `RunMetricSnapshotService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API.

#### Scenario: Deprecated path accepted
- **WHEN** client calls `POST /api/v1/analytics/run-metric-snapshots`
- **THEN** system SHALL behave identically to the same call against `POST /api/v1/run-metric-snapshots`

### Requirement: List run metric snapshots
`GET /api/v1/run-metric-snapshots` SHALL return metric binding snapshots for a run, grouped by computation.
Status: **Implemented**

#### Scenario: List snapshots by run
- **WHEN** client calls `GET /api/v1/run-metric-snapshots?filter=runId:eq:...`
- **THEN** system SHALL return all metric snapshots for that run, ordered by `computed_at_ms DESC`

#### Scenario: Required filter — runId
- **WHEN** client queries without `runId:eq:...` filter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Response includes full binding detail
- **WHEN** snapshots are returned
- **THEN** each snapshot SHALL include: `id`, `computationId`, `testSuiteRunId`, `tsmdId`, `tsmdName`, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings` (as JSON array), `inputBindings` (as JSON array), `outputSchema` (as JSON object), `computedAtMs`

#### Scenario: Deprecated path returns the same payload
- **WHEN** client calls `GET /api/v1/analytics/run-metric-snapshots?filter=runId:eq:...`
- **THEN** system SHALL return the same response body and status as the equivalent call to `GET /api/v1/run-metric-snapshots`

## ADDED Requirements

### Requirement: Deprecated run metric snapshot endpoint aliases
The service SHALL retain `/api/v1/analytics/run-metric-snapshots` as a deprecated alias for both `GET` and `POST`, so existing clients continue to work while they migrate to `/api/v1/run-metric-snapshots`. The alias SHALL delegate to the same service methods as the canonical paths and SHALL be removed in a future release.
Status: **Implemented**

#### Scenario: Alias is marked deprecated in OpenAPI
- **WHEN** the OpenAPI document is generated
- **THEN** both operations under `/api/v1/analytics/run-metric-snapshots` SHALL be marked `deprecated: true` and their descriptions SHALL name the replacement path

#### Scenario: Canonical operations are not marked deprecated
- **WHEN** the OpenAPI document is generated
- **THEN** the operations under `/api/v1/run-metric-snapshots` SHALL NOT be marked deprecated, and each operation SHALL carry its own operationId distinct from the alias operations

## Implementation notes

- Meta table created by `src/main/resources/db/migration/meta/POSTGRES/V1.32__CreateRunMetricSnapshotsTable.sql`.
- Repository: `data.db.repository.PostgresRunMetricSnapshotRepository` (`@Qualifier("metaDsl")`); service: `service.domain.RunMetricSnapshotService` (`@Transactional("metaTransactionManager")`).
- Canonical controller: `web.controller.RunMetricSnapshotController`; deprecated alias: a separate `@Deprecated(forRemoval = true)` controller delegating to the same service, so springdoc can mark only the alias deprecated and emit distinct operationIds.
- Filter whitelist: `data.db.repository.sql.FilterWhitelists.RUN_METRIC_SNAPSHOTS` backs the required `runId eq <uuid>` filter on both the canonical and deprecated `GET` endpoints; both paths are registered in `OpenApiQueryParamCustomizer`'s `REGISTRY` against this same whitelist.

The following bullets in the baseline `openspec/specs/metrics-storage/spec.md` Implementation Notes are now stale and MUST be replaced with the text given here when this change is archived (see `tasks.md` 8.7):

- **Supersedes** the "Service:" bullet's `RunMetricSnapshotService` reference: it moves from `service.domain.analytics` to `service.domain`.
- **Supersedes** the "Repository:" bullet's `PostgresRunMetricSnapshotRepository — same qualifier` (whose antecedent is `analyticsDsl`): the qualifier is now `metaDsl`, and the class moves from `data.db.analytics.repository` to `data.db.repository`.
- **Supersedes** the "Model:" bullet's `RunMetricSnapshot` reference: it moves from `data.db.analytics.model` to `data.db.model`; bindings remain `String` (raw JSON).
- **Supersedes** the "DTOs:" bullet's `RunMetricSnapshotResponseDto`, `RunMetricSnapshotBatchWriteRequestDto` reference: both move from `service.domain.dto.analytics` to `service.domain.dto`.
- **Supersedes** the "Migrations:" bullet's `V1.6__CreateRunMetricSnapshotsTable.sql` entry: that analytics migration is now frozen and unread — retained only so analytics `V1.8` and `V1.12` still apply on a fresh install. The live table is meta `V1.32__CreateRunMetricSnapshotsTable.sql`, backfilled once from the analytics copy by meta `V1_33__CopyRunMetricSnapshotsFromAnalytics.java`.
