## Context

The archived `introduce-dataset-entity` change made `Dataset` a first-class entity with one shape: every dataset is implicitly shareable across many `TestSuite`s. Real usage demands two ownership models — a curated catalogue (PUBLIC) and per-suite scratch datasets that must not pollute that catalogue (PRIVATE). The PRIVATE case adds two strong constraints: at most one suite may reference a PRIVATE dataset, and the dataset must not exist orphaned in steady state. Both must hold under concurrent suite creation/binding and across the API surface (create, update, delete, clone).

The parent branch (`feat/dataset`) has not shipped. The migration `V1.22__IntroduceDataset.sql` is therefore still mutable and we extend it in place rather than chasing it with a V1.23 that immediately rewrites its defaults.

Stakeholders: dataset/catalogue UX (FE), suite-run engine (snapshot phase, run start), clone flow, revalidation routing.

## Goals / Non-Goals

**Goals:**
- Two visibility modes (`PUBLIC`, `PRIVATE`) with a strong invariant: a PRIVATE dataset has exactly one bound suite from creation to deletion.
- Support the UX "create a suite first, then attach an existing PUBLIC dataset or create a new PRIVATE one" without ever observing an orphan PRIVATE dataset.
- Concurrency-safe binding: two parallel `POST /datasets` calls trying to attach the same PRIVATE dataset to different suites must produce exactly one success and one HTTP 409.
- Keep the "one dataset per suite" backfill model intact for V1.22 (every migrated suite gets its own PRIVATE dataset).
- Zero new infrastructure: no advisory locks, no application-level distributed coordination, no new datasources.

**Non-Goals:**
- Per-dataset ACLs, per-org sharing, or "shared with these suites" lists. Visibility is a binary catalogue-vs-scratch flag, not access control by principal.
- Admin/superuser endpoints for listing PRIVATE datasets across the system.
- Migrating away from V1.22's `dataset.id = source_suite.id` backfill identity. That remains a backfill-only invariant.
- Changing snapshot semantics — `datasetRef` does not learn about visibility.

## Decisions

### D1. Enforce "≤ 1 binding per PRIVATE dataset" with a PL/pgSQL constraint trigger on `test_suites`

The invariant spans two tables: `visibility` lives on `datasets`, the FK lives on `test_suites`. Three candidate enforcement mechanisms:
- **Denormalize `dataset_visibility` onto `test_suites`** so a partial unique index `UNIQUE (dataset_id) WHERE dataset_visibility='PRIVATE'` works. Adds a column that must be kept in sync on every visibility transition.
- **Pure app-level `SELECT … FOR UPDATE`** on the `datasets` row inside every binding transaction. The invariant lives only in Java; any future code path that updates `test_suites.dataset_id` without going through that gate silently breaks it.
- **PL/pgSQL constraint trigger** on `test_suites` (chosen). The trigger executes on every INSERT and on UPDATE-of-`dataset_id`, locks the target `datasets` row `FOR UPDATE`, and raises `ERRCODE='P0001'` (PL/pgSQL `RAISE EXCEPTION`) with MESSAGE TEXT `'PRIVATE_DATASET_ALREADY_BOUND'` when a second binding would land on a PRIVATE dataset.

The global `ExceptionHandler` inspects `SQLException.getSQLState()`; on `'P0001'` it returns HTTP 409 with `errorCode = PRIVATE_DATASET_ALREADY_BOUND`. The standard `23505 → UNIQUE_CONSTRAINT_VIOLATION` mapping path is untouched (no real PK/unique index is in play here — the trigger is the enforcement mechanism, and `P0001` cleanly separates this business rule from generic uniqueness violations).

The trigger is the only option that (a) keeps the invariant a DB-level fact attached to the column being modified and (b) avoids denormalization. The cost is one extra `SELECT … FOR UPDATE` per suite write — negligible given the write rate. Visibility-transition code (PUBLIC→PRIVATE) takes the same `FOR UPDATE` lock on `datasets` before counting bindings, so the transition and the trigger serialize on the same row lock and cannot race.

The trigger early-returns when `NEW.dataset_id IS NULL`, so unbind paths (rebind-to-null, PRIVATE-delete cascade) are not subject to the guard.

### D2. Atomic create-and-bind via `bindToSuiteId` on `POST /datasets`

Two alternatives were considered:
- **Nested endpoint `POST /test-suites/{id}/dataset`**: makes URL composition carry the binding but forks dataset creation into two endpoints depending on visibility.
- **Inline `newPrivateDataset` payload on `POST /test-suites`**: bundles dataset creation into suite creation, but blocks the "create suite first, decide on dataset later" flow we explicitly want.

Adding `bindToSuiteId` to the single `POST /datasets` endpoint keeps the dataset-centric URL, makes the requirement easy to validate at the DTO boundary (`@AssertTrue` cross-field rule: present iff `visibility=PRIVATE`), and lets the server transact "insert dataset" + "update suite" inside one method on `DatasetService`. The trigger from D1 fires on the `test_suites` update and trivially passes because the dataset has no other binding yet. There is never an observable orphan-PRIVATE state — the orphan window collapses to "uncommitted transaction" only.

### D3. Allow unbound suites by relaxing `test_suites.dataset_id` to NULLABLE

The UX flow ("create suite, then pick or create dataset") forces a brief unbound window for the suite. The alternatives — forcing bundled creation, or refusing to expose dataset selection until the suite has a placeholder dataset — were rejected as either inflexible (no late-binding) or surprising (a "default" dataset users didn't ask for).

The cost is one new "unbound" state that every dataset-touching operation must guard. The guard surface is small and centralized:
- **Run start** (`TestSuiteRunService` / `executeRunAsync` entry): refuse with HTTP 409 `SUITE_HAS_NO_DATASET` when `datasetId IS NULL`. The snapshot phase never sees an unbound suite.
- **Test case CRUD**: test cases live on `Dataset`, not `TestSuite`; suite-rooted endpoints that previously navigated `suite → dataset → test_cases` need null-checks. Per the dataset-entity refactor, test-case endpoints are dataset-rooted already, so this is a small surface.
- **Schema-dependent helpers** (`TemplateVariableService`, `DatasetSchemaProvider` callers in suite context): return an empty schema or skip when the suite is unbound, with no derived errors.

All other suite operations (rename, metric definitions, response columns, deployment binding) work normally on an unbound suite. List responses surface `datasetId: null`.

### D4. `DELETE /datasets/{id}` on a PRIVATE dataset atomically unbinds its suite

The alternatives — reject with 409 (forcing the user to delete the suite first, which then cascades back to the dataset) or cascade-delete the suite as well — both punish the natural workflow "I want to swap this suite's dataset for a fresh one". Unbinding the suite first, then deleting the dataset, in one transaction, mirrors the suite-side cascade decision (D5) and keeps suite config (metric defs, response columns, deployment binding) intact.

The implementation is two statements in a single `@Transactional("metaTransactionManager")`: `UPDATE test_suites SET dataset_id = NULL WHERE dataset_id = :id` followed by `DELETE FROM datasets WHERE id = :id`. Test cases cascade via the existing `test_cases.dataset_id ON DELETE CASCADE` FK. The trigger sees no second binding (the row being unbound is the only one) and does not interfere. For PUBLIC datasets, the existing FK-RESTRICT behavior is preserved.

### D5. Suite-delete cascades the bound PRIVATE dataset at the **service** layer, not via DB CASCADE

PostgreSQL cannot express "cascade only when the referenced row has `visibility='PRIVATE'`". A blanket CASCADE on `test_suites.dataset_id` would silently delete PUBLIC datasets when a suite that uses them is deleted — exactly the wrong default.

`TestSuiteService.delete(...)` already opens a meta transaction for file-storage cleanup. Inside that same boundary, after the suite row is deleted, the service inspects the captured `(datasetId, visibility)` pair and, if PRIVATE, calls `DatasetService.delete(datasetId)` (which executes the PRIVATE delete path from D4 — by now the unbind step is a no-op since the suite is already gone). Test cases cascade through the dataset's FK.

Suite deletion cascade-removes all of the suite's `test_suite_runs` (and therefore their stored `suite_snapshot` JSON rows) via the existing V1.6 `ON DELETE CASCADE` FK from `test_suite_runs.test_suite_id` to `test_suites(id)`. This is intentional and unchanged by this change: runs die with the suite. The PRIVATE-dataset cascade therefore leaves no dangling references — the dataset row, its test cases, the suite row, all of its runs, and all of those runs' snapshots are removed in the same transaction.

**DIAL file-storage cleanup semantics are unchanged.** Files live under `@ef/suites/{suiteId}/...`, keyed by suite (not dataset). The PRIVATE-dataset delete path (D4) keeps the suite alive — it only unbinds the suite and removes the dataset row — so no files are touched. Suite delete with PRIVATE-cascade (D5) already triggers the existing post-commit best-effort DIAL file cleanup keyed by `suiteId`. No new file-cleanup code path is introduced by this change.

### D6. Rebind/unbind from a PRIVATE dataset is rejected; user must `DELETE /datasets/{id}` first

A `PATCH /test-suites/{id}` that changes `datasetId` (including setting it to `null`) when the **old** binding is PRIVATE returns HTTP 409 `PRIVATE_DATASET_REBIND_FORBIDDEN`. The alternative — silently unbinding-then-deleting the old PRIVATE dataset during the rebind — would create implicit data loss exactly when the user is doing something destructive but expressed it as a routine update. Forcing the explicit `DELETE` keeps the loss path single-purpose and matches the rest of the API's "data loss is never a side effect" posture.

### D7. Visibility transitions go through a dedicated `PATCH /datasets/{id}/visibility` endpoint, using the same row lock as the trigger

Visibility is the only field on `Dataset` whose mutation is gated by cross-row business rules (binding count). Mixing that gate into the generic `PUT /datasets/{id}` payload requires either request-shape branching ("did the client send `visibility`? then run the transition path") or silently ignoring the field — both options leak the gate into the wrong layer and produce ambiguous client expectations. The dedicated endpoint isolates the transition into its own operation with its own request shape, error codes, and OpenAPI example, mirroring the rest of the API's "destructive or state-changing operations get a named endpoint" posture.

- **Endpoint:** `PATCH /api/v1/datasets/{id}/visibility`
- **Request body:** `{ "visibility": "PUBLIC" | "PRIVATE" }`
- **Service-layer logic** (inside `@Transactional("metaTransactionManager")` on `DatasetService`):
  1. `SELECT … FOR UPDATE` the `datasets` row.
  2. Count bound suites under the same lock (`SELECT count(*) FROM test_suites WHERE dataset_id = :id`).
  3. Validate the transition:
     - PRIVATE→PUBLIC: the dataset must currently have exactly 1 binding (it is PRIVATE, so by invariant D1 this is always true; the count check is defensive). Always allowed — relaxing a constraint.
     - PUBLIC→PRIVATE: bound-suite count must be exactly 1, else HTTP 409 `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`.
  4. Update the column.

Because every binding write also locks this row (D1), no concurrent binding can sneak in between the count and the column update.

Allowing PUBLIC→PRIVATE with 0 bound suites was rejected because it permits zombie PRIVATE datasets — reachable only by `findById`, unbound, and ineligible for cascade cleanup — exactly the state D2 was designed to prevent.

`PUT /api/v1/datasets/{id}` accepts `visibility` in the request body but **silently ignores it** (handled in `DatasetService.update`, not by removing the field from the DTO — see D8). This keeps a single DTO shape across create/update and routes all transition logic through the one endpoint that enforces it.

### D8. Single `DatasetRequestDto` for create + update; `visibility` is ignored on update

Update visibility transitions are gated by business rules (D7) that don't belong on the request DTO (`@NotNull` cannot express "required on create, ignored on update"). Splitting into `DatasetRequestDto` (create) and `DatasetUpdateDto` (update) duplicates ~12 fields for the sake of one validation difference. Instead, the DTO is unified: `visibility` is annotated `@NotNull` (for the create path) and on update the service reads only the fields it allows to change — `visibility` is silently dropped. Clients that want to change visibility use the dedicated `PATCH /api/v1/datasets/{id}/visibility` endpoint (D7). The OpenAPI examples make the intent explicit per operation.

### D9. Modify V1.22 in place; document local-DB checksum impact

A V1.23 that immediately rewrites V1.22's column defaults adds a second pass over the same table for no production benefit (V1.22 has not shipped). The cost is local developer environments that have already applied the pre-modification V1.22 — their Flyway history shows a mismatched checksum on next boot. The fix is to **drop and recreate the local DB**; `flyway repair` alone is NOT sufficient because it only fixes the history table's checksum, not the already-applied schema (the new column and trigger would be missing). CI starts from empty, so no impact there.

### D10. Visibility is not part of `suite_snapshot.datasetRef`

A snapshot is a frozen view of behavior-impacting inputs for a run: schema, response columns, deployment refs, etc. Visibility is access control over the live dataset, not run input. Snapshots created before V1.22 keep the v2 backfill defined by `introduce-dataset-entity` unchanged.

### D11. `visibility` is not in filter/sort whitelists

`GET /api/v1/datasets` hard-filters to `visibility='PUBLIC'` at the repository level. There is no `?includePrivate=true` opt-in and no `filter=visibility:eq:PRIVATE`. The whitelists in `FilterWhitelists` / `SortWhitelists` deliberately omit the column so OpenAPI's auto-generated docs match runtime behavior. If an admin/cross-tenant listing is ever needed, it goes through a separate endpoint, not a flag on the public one.

## Risks / Trade-offs

- **Trigger error message vs business error code.** The trigger raises `ERRCODE='P0001'` with a stable MESSAGE TEXT `'PRIVATE_DATASET_ALREADY_BOUND'`. The global exception handler inspects `SQLException.getSQLState()` for `'P0001'` and maps it to HTTP 409 with `errorCode = PRIVATE_DATASET_ALREADY_BOUND`. To get the precise error code on non-race paths (typical user-error case) without requiring a DB round-trip to the trigger, `DatasetService` and `TestSuiteService` also perform an app-level pre-check that yields the same typed business exception; the trigger remains the backstop for the actual race. **Mitigation:** keep the app-level check and the trigger in lockstep — both encoded against the same predicate, both covered by the concurrent-binding functional test. The standard `23505 → UNIQUE_CONSTRAINT_VIOLATION` mapping path is untouched.
- **Local-DB checksum mismatch after V1.22 in-place edit.** Devs who have already applied the pre-modification V1.22 see a Flyway checksum error on next boot. **Mitigation:** documented in proposal Impact section — drop and recreate the local DB. `flyway repair` alone is NOT sufficient because it only updates the history checksum without re-running the schema DDL, leaving the new column and trigger missing. CI is unaffected.
- **Unbound-suite blast radius.** Every code path that previously read `suite.getDatasetId()` non-null may now NPE. **Mitigation:** add an ArchUnit-style audit pass (search for `.getDatasetId()` call sites under `service.domain`) during implementation; centralize the null check in the run start guard and the dataset-schema lookup.
- **`DatasetRequestDto` ambiguity on update.** A client sending `visibility=PUBLIC` on `PUT /datasets/{id}` for a currently-PRIVATE dataset may reasonably expect a transition but the service silently ignores the field. **Mitigation:** the OpenAPI example for `PUT /datasets/{id}` explicitly shows `visibility` absent and documents that transitions go through the dedicated `PATCH /api/v1/datasets/{id}/visibility` endpoint (D7); the field is accepted on PUT (single DTO shape) but dropped by `DatasetService.update`.
- **Concurrent `PUBLIC→PRIVATE` and second-suite-binding.** Both paths lock the `datasets` row; the second to arrive blocks until the first commits. After commit, the trigger sees `visibility='PRIVATE'` and rejects the late binder, or the transition aborts with `INVALID_BINDING_COUNT`. **Mitigation:** functional test (parallel threads) covering the race.
- **Snapshot-only runs against a deleted PRIVATE dataset.** When a suite is deleted, its runs are also gone via the existing V1.6 `ON DELETE CASCADE` FK on `test_suite_runs.test_suite_id` (D5), so there is no surviving-run/missing-suite scenario to worry about for the suite-delete path. The remaining case is a run whose suite still exists but whose dataset has been deleted via the standalone PRIVATE delete path (D4: the suite is unbound, the dataset row removed): the live dataset is null but the run's `suite_snapshot` is self-contained (snapshot v2 carries `datasetRef` + `testCaseSchema`) and remains inspectable. **Mitigation:** none needed — covered by the existing snapshot resolution path.

## Migration Plan

1. **Modify V1.22 in place** — add `datasets.visibility` (CHECK constraint, NOT NULL, no default), backfill all existing rows to `'PRIVATE'`, relax `test_suites.dataset_id` to NULLABLE, append the constraint trigger function and trigger.
2. **Regenerate jOOQ** — `./gradlew generateJooq`. Commit the diff under `src/main/java-generated/data/db/jooq/meta/`.
3. **Update Java model + DTOs** — new `DatasetVisibility` enum, nullable `TestSuite.datasetId`, DTO field additions and validation rules.
4. **Update repositories** — list-predicate filter, `countBoundSuites`, `unbindAllSuites`, nullable writes for `test_suites.dataset_id`.
5. **Update services** — `DatasetService` (atomic create+bind, transition, PRIVATE delete), `TestSuiteService` (nullable binding, rebind rejection, suite-delete cascade), `TestSuiteRunService` run start guard.
6. **Update controllers + OpenAPI** — payload schemas, examples, error codes.
7. **Tests** — unit tests for transition/cross-field validation, functional tests for the API surface and concurrency race.
8. **Docs** — `docs/database-schema.md`, AGENTS.md Dataset Entity section.

### Spec deltas — baseline scenarios to be REPLACED (not amended)

When delta specs are authored:
- `test-suites/spec.md` scenario **"Suite delete does not cascade to dataset"** (around lines 69-71 of the current baseline) must be **REPLACED** (not merely amended) with a visibility-conditional rule: PUBLIC datasets remain unaffected by suite deletion; PRIVATE datasets are cascade-deleted via the new D5 logic.
- `datasets/spec.md` scenario **"Delete dataset rejected by RESTRICT"** (around lines 73-75 of the current baseline) must be **REPLACED** with a PUBLIC-only restriction; PRIVATE datasets with exactly one suite binding follow the new D4 unbind-then-delete path.

Failing to remove the old scenarios will produce a contradictory spec after merge.

**Rollback:** revert the branch. No production data is at risk because the parent change has not shipped. For local devs mid-stream: drop and recreate the local DB.

## Open Questions

- **Visibility on suite clone**: the `test-suite-clone` spec already requires a `datasetId` override. When the source suite's dataset is PRIVATE, the clone must point at a different dataset (creating a new PRIVATE one inline would require yet another shape). Confirm with the clone spec delta whether to forbid PRIVATE sources outright or require an explicit new-PRIVATE override field — likely the spec delta is added as part of this change.
