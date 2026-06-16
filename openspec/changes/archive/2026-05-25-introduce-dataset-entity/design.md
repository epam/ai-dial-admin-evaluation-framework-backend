## Context

Test cases today belong to exactly one `TestSuite` (FK `test_cases.test_suite_id` with `ON DELETE CASCADE`). The schema describing those cases — `testCaseSchema`, a JSONB list of `FieldDefinitionDto` — also lives on the suite. The only reuse mechanism is `TestSuiteCloneService`, which copies cases into a fresh suite, after which both copies drift independently.

We are introducing `Dataset` as the centralized container that owns both the schema and the test cases. Each `TestSuite` becomes a consumer that references one `Dataset` via a new mandatory FK. Many suites may share one dataset, so edits to the dataset's test cases propagate to every consumer without copy/sync logic.

Snapshot mechanics for runs remain intact: at run start the suite still freezes its execution context into `test_suite_runs.suite_snapshot` and materializes per-case rows into `test_case_run_inputs`. The snapshot DTO gains a `datasetRef` and the source of `testCaseSchema` shifts from suite to dataset, but the lifecycle (capture once at PENDING→RUNNING, treat as immutable thereafter) is unchanged.

The change runs against a brownfield deployment with live suites and test cases. The Flyway migration includes inline data backfill so the application can boot on the new schema without any post-migration scripts.

## Goals / Non-Goals

**Goals:**
- Establish `Dataset` as the system of record for test case shape and data.
- Re-root `TestCase` under `Dataset`; keep test-case FKs and downstream services correct.
- Add `TestSuite.datasetId` as a mandatory reference and `TestSuite.disabledTestCaseIds` as a per-suite exclude list.
- Preserve operational behavior across the migration: existing suites continue to run with the same data and the same set of enabled cases.
- Land DDL and data backfill in a single Flyway migration so the application's first boot on the new code is against a fully populated schema.
- Extend `RevalidationTask` to cover dependent suites without changing its task-row shape.
- Add `datasetRef` and bump `SuiteSnapshotDto.CURRENT_VERSION` to `"2"` while keeping `"1"` deserializable for in-flight runs.

**Non-Goals:**
- Automated consolidation of the post-migration 1:1 dataset-per-suite shape into a many-to-one shape. That is a user-driven workflow via the new dataset CRUD endpoints.
- File-reference path-scheme reform for `@ef/suites/{suiteId}/...` strings embedded in `TestCase.data`. Current scheme is preserved; reform is tracked as a follow-up.
- Splitting `RevalidationTask` into dataset-rooted vs suite-rooted variants, partial-failure semantics, or suite-phase progress counters.
- Per-case overrides of suite-level templates/bindings (dropped entirely from the model).
- Async revalidation triggered by suite PUT. Suite edits continue to validate synchronously via `SuiteValidationService` at PUT time.
- Backward-compatible API shims. Breaking changes to test-case endpoints, suite DTOs, snapshot DTO, and revalidation-task DTO are accepted; the frontend will follow.

## Decisions

### D1. `dataset.id = source_suite.id` during migration

For every existing `TestSuite` we insert exactly one `Dataset` row carrying the same UUID. This makes the FK rename on `test_cases` (`test_suite_id → dataset_id`) a pure metadata change — no row updates, no data shuffling — and lets `test_suites.dataset_id` be populated via `SET dataset_id = id`. Revalidation-task rows are likewise rebound by retargeting the FK without touching the value.

*Alternatives considered:*
- *Generate fresh dataset UUIDs and rewrite `test_cases.test_suite_id`* — requires a row-by-row UPDATE on every test case (potentially the largest table), introduces transactional risk on large environments, and gains nothing because the post-migration shape is still 1:1.
- *Defer data migration to a separate OpenSpec change* — leaves the application in a state where it cannot boot against pre-existing data; pushes the hard problem out without reducing it.

### D2. `disabledTestCaseIds` as a denormalized JSONB array on `TestSuite`

Per-suite case exclusion lives in `test_suites.disabled_test_case_ids`, a JSONB array of UUIDs (default `'[]'`). At run-snapshot time the SQL filter does `NOT IN (...)` or anti-join against the list. Stale IDs (cases deleted from the dataset) are tolerated — set-membership naturally ignores them.

*Alternatives considered:*
- *Junction table `test_suite_test_cases (suite_id, test_case_id, enabled BOOLEAN)`* — strictly more flexible (per-(suite, case) attributes could be added later) but adds a join on every hot path: snapshot phase, count, list. Was rejected by the user in favor of the simpler shape with explicit note that we'd revisit if more per-(suite, case) attributes are needed.
- *Keep `enabled` on `TestCase`* — would make the flag dataset-global; toggling a case would affect every suite using the dataset. Loses per-suite control entirely.

### D3. Drop `requestTemplateOverride` and `inputBindingsOverride` entirely

Both fields currently live on `TestCase`, overriding the *suite's* `requestTemplate` and `inputBindings` for that specific case. Once cases are shared across suites these fields become ambiguous (which suite's template are they overriding?) and we have no junction table to scope them.

**Cross-model alignment with `TestCaseRunInput`.** `data.db.model.TestCaseRunInput` (the run-snapshot row) currently mirrors the dropped TestCase fields (`requestTemplateOverride`, `inputBindingsOverride`). The model class SHALL retain both fields so that legacy `test_case_run_inputs` rows written before this change continue to deserialize without schema drift. The DB columns are kept nullable for the same reason. New rows written by the snapshot phase (`TestSuiteEvaluationJob.attemptSnapshot()`) and by the legacy-fallback synthesis in `InProcessEvaluationExecutor.fetchPage(...)` SHALL set both override fields to `null` literally — there is no longer any source `TestCase` accessor to read from. This is captured as task 10.4a so the apply phase doesn't leave the snapshot-write call sites referencing methods that 7.1 has just removed.

*Alternatives considered:*
- *Move into the junction table* — same flexibility/complexity trade-off as D2; rejected for the same reason.
- *Keep them as dataset-global overrides* — semantically odd; the suite-level templates they referenced are per-suite, so a single override value cannot meaningfully target multiple suites.

The pre-existing values are dropped with the columns. This is a known data loss documented in the proposal.

### D4. Single dataset-rooted `RevalidationTask` with two sequential phases and asymmetric failure handling

One task row per dataset PUT that mutates `testCaseSchema`. Two sequential phases with **different failure modes**:

- **Phase 1 — test cases (fail-fast)**: coerce and revalidate every test case in the dataset (current logic, rescoped). Any uncaught exception aborts the task: status transitions to `FAILED`, `errorMessage` captures the cause, no suite revalidation runs. Rationale: a Phase 1 failure indicates a systemic problem (DB serialization, OOM, malformed schema) and progressing into Phase 2 against partially-revalidated data risks corrupting suite validity flags. The existing per-test-case `updateDataIfUnchanged` / `updateValidationIfUnchanged` rows-affected=0 path (concurrent test-case edit) continues to skip-and-continue at the row level — that's a guard miss, not an exception.

- **Phase 2 — dependent suites (per-suite resilient)**: walk every `TestSuite` where `dataset_id = task.datasetId` and run `SuiteValidationService.validate(...)` + `TestSuiteMetricDefinitionService.revalidateAllForSuite(...)` per suite. Each suite's work is wrapped in its own `try { ... } catch (Exception e) { log.warn(...); }` block — if revalidating one suite throws, the exception is logged at WARN with the suite id and full stacktrace (per the project's SLF4J convention — exception as last vararg), and the loop proceeds to the next suite. The task ends `COMPLETED` even when individual suites failed. The version-mismatch case (suite was edited mid-flight) is one specific cause subsumed under this generic handler; mid-flight edits would also surface as a per-suite optimistic-concurrency failure caught at the same boundary.

The per-suite catch is **deliberately broad** (catches `Exception`, not specific types) — this is a batch-resilience boundary, the same pattern as the existing outer `try/catch` around `runRevalidationAsync`. The project's "catch specific exceptions" rule has implicit carve-outs for these top-of-stack boundaries that exist to keep a long-running job going across per-item failures.

Existing counters (`totalCases`, `processedCases`, `validCount`, `invalidCount`, `coercedCellCount`) reflect Phase 1 only. Per-suite failures during Phase 2 are visible only in logs at this iteration — no per-task structured report, no new `suiteFailureCount` column. Structured per-suite outcomes (a separate failures table, partial-status enum, or per-suite progress counters) are deferred.

*Alternatives considered:*
- *Two task types (`DatasetRevalidationTask` + `TestSuiteRevalidationTask`)* — gives independent progress visibility per suite and natural partial-failure semantics, but doubles the schema, repos, services, and API surface for marginal benefit at this stage.
- *Single task with per-entity counter columns (`totalSuites`, `processedSuites`, etc.)* — adds suite-phase visibility without splitting the table, but the user opted for the simpler shape now and explicit follow-up later.
- *Symmetric fail-fast in both phases* — simpler, but means one mis-configured suite (e.g., a binding referencing a removed field while the user is mid-edit) aborts revalidation for every other dependent suite. Per-suite resilience scales naturally with dataset fan-out and aligns Phase 2 with the "many independent units of work" shape it actually has.
- *Symmetric resilience in both phases* — would let Phase 1 limp through partial test-case revalidation under systemic errors; rejected because Phase 1 is a single coercion-and-validate sweep that the rest of the system assumes either ran to completion or did not run, and partial state there has no good consumer.

### D5. `DatasetSchemaProvider` to break the would-be circular dependency

`DatasetService` needs to mutate datasets and `TestSuiteService` needs to validate suite bindings against the dataset's schema. A direct `TestSuiteService → DatasetService` injection would force `DatasetService → TestSuiteService` for the Phase-2 fan-out, creating a cycle. We introduce a thin `DatasetSchemaProvider` component that returns `List<FieldDefinitionDto>` for a given `datasetId`. Both `DatasetService` and `TestSuiteService` inject the provider; the provider has no other dependencies.

*Alternatives considered:*
- *Resolve the schema at the controller layer per request* — works but spreads the resolution policy across every controller that needs it (suite CRUD, snapshot, template variables, metric validation).
- *Use Spring's `@Lazy` to break the cycle* — works mechanically but hides the dependency direction and surprises maintainers.

### D6. `ON DELETE RESTRICT` for `test_suites.dataset_id`

Deleting a dataset returns HTTP 409 listing dependent suite names. Users must drop or rebind suites first. Test cases owned by the dataset cascade-delete with the dataset (they have no independent existence).

*Alternatives considered:*
- *`CASCADE`* — one DELETE wipes every suite that referenced the dataset. High blast radius; risky for shared datasets which is the entire point of the entity.
- *Soft delete (archived flag)* — useful for audit but adds a new lifecycle state to manage; deferred.

### D7. Snapshot version bump to `"2"`; `"1"` remains readable

`SuiteSnapshotDto` adds `datasetRef`. Existing serialized snapshots (version `"1"`) have all required fields embedded and continue to deserialize cleanly (`@JsonIgnoreProperties(ignoreUnknown = true)` + `@Builder.Default`). `SuiteSnapshotBuilder` writes version `"2"` for every new snapshot.

`SuiteSnapshotBuilder` takes `(TestSuite, Dataset)` as arguments rather than fetching internally. `TestSuiteEvaluationJob.attemptSnapshot()` fetches the dataset alongside the suite under the same `REPEATABLE_READ` transaction and passes both in, avoiding a double fetch.

`resolveSnapshot()` legacy-fallback path (run created before snapshot feature, `suite_snapshot IS NULL`) must redirect to the live dataset when synthesizing — the live suite no longer carries `testCaseSchema` after migration.

### D8. Inline data backfill in the Flyway migration

DDL and `INSERT INTO datasets SELECT FROM test_suites` ship in one file. The application's first boot on the new code is against the fully migrated schema. This is acceptable because the structural decision (D1) keeps the backfill simple: one INSERT, two correlated UPDATEs, no row-by-row processing.

*Alternatives considered:*
- *Two-phase deploy (additive migration, app deploy, cleanup migration)* — gives zero-downtime semantics but adds two release boundaries and operational complexity. Not warranted for this codebase's deployment cadence.
- *External migration script* — moves the same logic out of Flyway, loses idempotency guarantees, and adds a separate runbook.

### D9. Endpoint relocation, not preservation

Test-case CRUD, bulk PATCH, CSV import/preview, and CSV/ZIP export move from `/api/v1/test-suites/{suiteId}/test-cases/*` to `/api/v1/datasets/{datasetId}/test-cases/*`. Revalidation-task subroutes move from suite to dataset. The `includeEnabled` query param on export is dropped because the disable list is now per-suite, and the export is per-dataset — the parameter would have no canonical interpretation.

*Alternatives considered:*
- *Dual-mounting endpoints (suite path → fetch dataset behind the scenes)* — preserves the FE contract but lies about the data shape (suites no longer own cases). Rejected to keep the model honest.

### D10. `SchemaValidationService` cache re-keyed by `datasetId`

`SchemaValidationService` currently caches schema-derived validators keyed by `testSuiteId` and evicts on suite PUT/DELETE. With schema ownership moving to `Dataset`, the cache SHALL be **re-keyed by `datasetId`**. Eviction triggers move to `DatasetService.update` (when `testCaseSchema` diffs against stored) and `DatasetService.delete`. Suite PUT/DELETE no longer evicts schema-validator cache entries because suites are no longer the schema owner; suite rebinds (changing `datasetId`) simply look up the new dataset's cached entry, populating on miss.

This is binding, not deferred: the apply phase must rename `SchemaValidationService` cache keys and move the eviction calls from `TestSuiteService` to `DatasetService`. Without this, stale cached validators built off the pre-migration suite-id key would never be invalidated and would diverge from the live dataset schema after the first dataset PUT.

*Alternatives considered:*
- *Rebuild from scratch on every dataset PUT* — simple but defeats caching for hot dataset-validator paths (snapshot phase fan-out, revalidation Phase 1).
- *Hybrid key (`datasetId, datasetVersion`) with no explicit eviction* — natural fit for optimistic concurrency, but leaks memory across version bumps unless paired with a TTL or size limit. Defer to v2 if the simple per-dataset-id eviction misbehaves.

## Risks / Trade-offs

- **Data loss in dropped override columns (D3)** → Documented in proposal; user-acknowledged. If pre-existing environments have non-null overrides, add a pre-drop diagnostic `RAISE NOTICE` in the migration listing affected case IDs so operators see what's being discarded.
- **N suites paired with N visually-distinct datasets post-migration** → Each migrated dataset's `name` is its source suite's name with the literal prefix `DATASET_` (e.g. suite `CustomerSupport` → dataset `DATASET_CustomerSupport`); UUIDs are identical (D1). The prefix makes the suite/dataset split unambiguous in listings without forcing operators to compare UUIDs. Frontend should surface "this dataset is used by 1 suite" badges so users naturally find consolidation candidates. Cap risk: `datasets.name` is `VARCHAR(255)` (mirroring `test_suites.name`), so any pre-existing suite with a name longer than 247 characters will overflow the prefix at insert time and abort the migration; this is acceptable given that suite names are validated to ≤ 255 chars at create/update and the practical distribution is well below 247.
- **Stale IDs in `disabled_test_case_ids` after test-case deletion** → Tolerated. Set-membership filtering at snapshot time naturally ignores them. Periodic cleanup is a follow-up.
- **`RevalidationTask` task-row stays RUNNING after JVM crash** → Pre-existing limitation, not regressed. Existing `TIMED_OUT` status handles the no-progress case for stuck tasks.
- **Concurrent suite edit during Phase 2 of a dataset revalidation** → Subsumed by the per-suite resilience boundary (D4). A version mismatch surfaces as an exception caught by the per-suite handler, logged, and the loop moves on. The user can re-trigger revalidation if needed; the next pass picks up the new suite state.
- **Concurrent dataset PUT while a task is RUNNING** → The `datasets/spec.md` scenario "Concurrent dataset PUT while task is RUNNING" requires sequential per-dataset task scheduling. **Mechanism**: the existing `RevalidationService` task executor picks up at most one task per dataset at a time by filtering `WHERE dataset_id = ? AND status IN ('PENDING','RUNNING')` when deciding whether to start a newly-inserted PENDING task, and skipping pickup if any RUNNING task already exists for that dataset. The newly-spawned task remains in PENDING until the executor's next scan finds the dataset has no RUNNING task; ordering across queued PENDINGs for the same dataset is FIFO by `created_at`. No new column, no row-level lock — the (dataset_id, status) index on `revalidation_tasks` makes the gate query cheap. The in-flight task continues against the schema it was started with; the newly-queued task runs against the post-PUT schema. This is acceptable for v1 because (a) per-dataset write throughput is low, and (b) a stuck RUNNING row is bounded by the existing `revalidation.timeoutMinutes`-based `TIMED_OUT` transition.
- **Snapshot legacy fallback path** → Code-level risk: `resolveSnapshot()` must redirect to live dataset; otherwise legacy runs that lost their snapshot will fail. Covered by a functional test that creates a legacy-shaped run row (NULL `suite_snapshot`) and asserts synthesis succeeds via the dataset.
- **jOOQ generation lag** → If `./gradlew generateJooq` is not re-run, the build fails with a type mismatch on every renamed column. `JooqSchemaDriftTest` enforces this in CI; PR cannot merge without the regenerated diff committed.
- **Single-task revalidation under heavy fan-out** → For datasets with hundreds of dependent suites, Phase 2 may run long enough to hit the configured `revalidation.timeoutMinutes`. With per-suite resilience (D4) a single problem suite no longer aborts the run, but the total wall-clock time still grows linearly with suite count. Acceptable for v1; a future change can introduce parallelism per suite or split Phase 2 into independently-scheduled work (Out of Scope §2 in proposal).
- **Per-suite failures only visible in logs** → A dataset PUT that triggers revalidation will return `202 RevalidationTaskDto` and the task will eventually transition to `COMPLETED` even if every dependent suite failed Phase 2. Operators must inspect logs to discover suite-level failures. This is an explicit trade-off for the simpler task-row shape; structured per-suite outcomes are tracked as a follow-up.
- **Risk — `disabledTestCaseIds` cap exceeded by existing data**
  The migration backfills `test_suites.disabled_test_case_ids` via `jsonb_agg(test_cases.id WHERE is_enabled=false)`. Any pre-existing suite whose disabled-case count exceeds `MAX_DISABLED_TC_IDS = 10000` (introduced in this change) will end up with an over-sized list that violates validation on the next PUT. Mitigation: the migration SHALL emit a `RAISE NOTICE` listing any suite with `count(*) FILTER (WHERE is_enabled=false) > 10000` so operators can decide whether to truncate before re-saving. If telemetry already shows the largest disabled-case count is well under the cap in production, this risk is acceptable.
- **Risk — Synchronous schema-driven cleanup on dataset PUT can serialize with snapshot-phase reads**
  The dataset PUT path runs schema-driven cleanup (remove orphan keys from every TestCase under the dataset) synchronously within the PUT transaction. For datasets with hundreds of thousands of test cases, this lengthens the transaction and may serialize with the snapshot-phase `findValidByDatasetIdExcludingIds` of any concurrent suite run. Decision: ACCEPTED — keep cleanup synchronous within the PUT transaction. Rationale: the `datasets/spec.md` "Schema-driven data cleanup" requirement guarantees no TestCase carries orphan fields by the time the PUT returns 202; batching across transactions or deferring to RevalidationTask Phase 1 would violate that guarantee. Mitigation against transaction-length pathology: rely on current product limits on TestCases per dataset (validated by `7.4b`'s 10k-row capacity test) and existing PostgreSQL deadlock/serialization-retry behavior in `executeRunAsync`. If telemetry shows long-running PUTs in production, revisit and either (a) raise the spec to permit partial cleanup with a structured warning, or (b) introduce a configurable "deferred-cleanup" mode behind a feature flag.
- **Risk — File references after suite clone do not match cloned-suite ownership scope**
  Test cases under a shared dataset hold file references with paths like `@ef/suites/{originSuiteId}/...`. After this change a dataset may be referenced by multiple suites; cloning a suite (with or without a new dataset) does not rewrite these paths. `FileRefValidator` (see `file-ref-validation` spec) scopes ownership per suite, so the validator will surface warnings on cloned/secondary-suite operations. Interim behavior: warnings only (not blocking). Follow-up change: extend `FileRefValidator` to scope ownership at the dataset level (or pre-rewrite file refs on clone). Note: this is acknowledged as a follow-up in `specs/test-suite-clone/spec.md`; the risk entry exists to make it visible at the design level.

## Migration Plan

### Deployment sequence (single release)

1. Branch carrying: new Flyway file, new + modified Java sources, regenerated jOOQ sources, updated `docs/database-schema.md`, updated `openspec/specs/README.md`.
2. Pre-deploy: take a database backup (rollback strategy).
3. Deploy the new build. Flyway applies `V1.<next>__IntroduceDataset.sql` automatically on startup, running both DDL and inline backfill in a single transaction (where Postgres supports DDL-in-tx — which it does for this set).
4. Application boots against the migrated schema. All existing suites operate immediately; existing test cases are now FK'd to their auto-created dataset.
5. Smoke checks: list datasets (expect one per pre-existing suite), pick one suite, list its dataset's test cases (expect identical set), start a run (expect snapshot v2 with `datasetRef` populated).

### Rollback strategy

Flyway forward migrations do not support automated rollback. If the migration succeeds but the application later misbehaves in a way attributable to the change:
- For non-data-loss issues: redeploy the previous build pointed at the migrated database (the previous code does not understand `dataset_id` / `disabled_test_case_ids` and will fail to start — so this path is not viable).
- For data-loss issues: restore from the backup taken in step 2. The restored database is pre-migration; the previous build can boot against it. No partial-state recovery path is supported.

### Code changes that must ship in the same release

These are not migration steps but must accompany the migration in the same PR/release; otherwise the application boots into a broken state:
- jOOQ regeneration (renamed columns, new table).
- `SuiteSnapshotBuilder.resolveSnapshot()` redirect to live dataset for the NULL-snapshot fallback path.
- `RevalidationTaskRepository`, `TestCaseRepository`, `TestSuiteRepository` jOOQ where-clause rebinds.
- Controller path rebases and `TestCaseBulkSelectorResolver` rescoping to dataset.

## Open Questions

1. **Pre-drop diagnostic for override columns** — should the migration emit a `RAISE NOTICE` (or `RAISE WARNING`) listing case IDs that had non-null `request_template_override` / `input_bindings_override` before the columns are dropped? Low cost, high audit value. Lean yes; confirm during apply.
2. Q2: ~~**`TestSuiteCloneRequestDto.datasetId` override** — should the clone request accept an optional `datasetId` so a user can clone a suite onto a different dataset, or is the source dataset implicit?~~ Resolved — `datasetId` is supported; see `specs/test-suite-clone/spec.md` "Clone with explicit datasetId override" and tasks 6.7, 11.2.
3. Q3: ~~If two PUTs arrive while the first task is RUNNING…~~ Resolved by `datasets` spec scenario "Concurrent dataset PUT while task is RUNNING": the new task is enqueued with status PENDING; the in-flight task continues against its original schema; the new task runs sequentially after.
