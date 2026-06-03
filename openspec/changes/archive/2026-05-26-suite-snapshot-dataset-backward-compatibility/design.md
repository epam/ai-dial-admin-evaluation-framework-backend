## Context

The `introduce-dataset-entity` change (Flyway `V1.22__IntroduceDataset.sql`, currently on a WIP branch and not yet deployed) introduced `SuiteSnapshotDto.datasetRef` and bumped `CURRENT_VERSION` to `"2"`. Stored `test_suite_runs.suite_snapshot` JSONB blobs written before V1.22 lack both `snapshotVersion` and `datasetRef` keys.

The current `SuiteSnapshotDto` (post-V1.22) declares:

```java
@Builder.Default
private String snapshotVersion = CURRENT_VERSION;  // "2"
```

The historical (pre-V1.22) `SuiteSnapshotDto` declared `private String snapshotVersion = "1"` as a plain field initializer. Jackson serializes that field unconditionally, so every snapshot blob produced by that code carries `"snapshotVersion": "1"` explicitly in the stored JSON. Under post-V1.22 application code, those blobs flow through `resolveSnapshot` as follows:

```
                  Pre-V1.22 stored JSON
                  {
                    "snapshotVersion": "1",   ← explicit, written by old producer
                    "suiteType": "DEPLOYMENT",
                    "deploymentRef": {...},
                    ...
                    // no "datasetRef"
                  }
                          │
                          ▼
                  Jackson + @Builder.Default
                          │
                          ▼
                  SuiteSnapshotDto {
                    snapshotVersion = "1"   ← populated from JSON
                    datasetRef      = null  ← absent in JSON
                  }
                          │
              resolveSnapshot version gate
                  (CURRENT_VERSION = "2")
                          │
                          ▼
                  throws UnsupportedSnapshotVersionException
                          │
                          ▼
                  Run cannot be read; historical run is unexecutable
```

The version gate correctly rejects v1 blobs — that is its purpose. But that rejection means every run created before V1.22 is unexecutable after V1.22 is deployed: detail reads, re-runs, and any code path that calls `resolveSnapshot` for a historical run fails. Backfilling those rows to a uniform v2 shape during V1.22 lets historical runs read normally and lets us treat any future non-v2 snapshot encountered at runtime as a genuine producer bug (or a forward-compatibility signal for a hypothetical v3), not an expected legacy-data case.

The existing `openspec/specs/suite-run-snapshot/spec.md` describes a different intended behavior (`@Builder.Default = "1"`, v1 stays readable with `datasetRef = null`). That spec was authored under the original design intent of `introduce-dataset-entity` and is now drifted from the implementation. This change does not preserve the drifted intent; it makes the post-deployment world uniformly v2 by mutating stored blobs and aligns the spec with that reality.

Because V1.22 is on a WIP branch and has not been applied to any production environment, the cheapest correct fix is to append a JSONB `UPDATE` step inside V1.22 itself — not a new Flyway file. This keeps the migration history clean and ensures the application's first boot against the V1.22 schema sees a uniform v2 snapshot shape.

## Goals / Non-Goals

**Goals:**

- After V1.22 runs, every row in `test_suite_runs` where `suite_snapshot IS NOT NULL` carries `snapshotVersion = "2"` and a populated `datasetRef` ({id, version, name}) — regardless of when the snapshot was written.
- The synthesized `datasetRef` is deterministic from the joined `test_suites` row, leveraging the `dataset.id = source_suite.id` invariant established by D1 of `introduce-dataset-entity`.
- The backfill is co-located with the existing migration so there is no out-of-order DDL/data sequence and no separate Flyway file to track.
- The application's `resolveSnapshot` path sees a single, uniform shape for all stored snapshots; downstream code that reads `snapshot.getDatasetRef().getId()` never observes null on a non-null snapshot.
- A functional test covers the path: insert a pre-V1.22-shaped `suite_snapshot` JSON, run the migration (via the standard Testcontainers spring boot path), assert `resolveSnapshot` returns a snapshot with populated `datasetRef`.
- The existing `suite-run-snapshot` main spec is updated so its requirements match the post-backfill reality and resolve the existing drift between spec text and code.

**Non-Goals:**

- Introducing a new Flyway file (`V1.23__...`). V1.22 is unreleased, so editing it in place is correct.
- Java code changes to `SuiteSnapshotDto`, `SuiteSnapshotBuilder`, or `resolveSnapshot`. The post-backfill data shape means no code path observes the legacy case anymore.
- Removing the `UnsupportedSnapshotVersionException` path. It becomes vestigial in practice but stays as defense-in-depth for forward compatibility with a hypothetical v3.
- Backfilling `test_case_run_inputs` rows. Those rows already exist for runs that completed the snapshot phase; their schema (`request_template_override`, `input_bindings_override` retained as nullable per `introduce-dataset-entity` D3) is unchanged.
- Handling the `suite_snapshot IS NULL` case. Those rows route through `resolveSnapshot`'s legacy-fallback synthesis path (`SuiteSnapshotBuilder.build(testSuite, dataset)`), which already produces a fully-populated v2 snapshot.
- Pruning runs whose suite was deleted before migration. `test_suite_runs.test_suite_id` has `ON DELETE CASCADE`, so no such rows exist; the join is guaranteed to match.

## Decisions

### D1. Append backfill to V1.22, do not add a new Flyway file

V1.22 has not been applied to any environment. Adding `V1.23__BackfillSuiteSnapshotDatasetRef.sql` would leave a "dataset entity exists but old snapshots are broken" window between V1.22 and V1.23 that no environment will ever observe. Keeping both steps in V1.22 means the application's first boot on the new schema sees a fully-consistent world.

*Alternatives considered:*

- *Add `V1.23__BackfillSuiteSnapshotDatasetRef.sql`* — adds migration history noise for a WIP-only file; future archaeology has to reconstruct that V1.22 + V1.23 were always meant to be one logical step. Rejected because the cost (one extra file) outweighs the benefit (cosmetic separation) when V1.22 is still editable.
- *Move both steps into a separate migration `V1.22__IntroduceDatasetAndBackfillSnapshots.sql` (rename V1.22)* — Flyway tracks files by checksum; renaming the V1.22 file is equivalent to "the migration we have now never existed" which is fine for an unreleased branch but offers no advantage over editing in place. Rejected on simplicity.

### D2. Place the new UPDATE step at the end of V1.22

The backfill depends on:

1. `datasets` table populated (V1.22 step 2 — needed for the `version` value, currently hard-coded `1` but conceptually "the dataset's initial version").
2. `test_suites` table still contains the original `name` column (V1.22 step 4-7 don't touch `name`, so this is satisfied throughout).

The backfill does NOT depend on the order of steps 8-10 (test_cases column rename, revalidation_tasks retarget) and is not depended on by them. Placing it at the end (after step 10) keeps the migration's logical flow intact: schema changes first, then data backfill, then cleanup of dependent FK relationships. Specifically, the new step becomes **step 11**.

*Alternative considered:* place the step immediately after step 2 (datasets backfill). Symmetry with the dataset-backfill INSERT is appealing, but interrupts the suite/test_cases column-drop sequence in steps 3-8 and forces the reader to context-switch. Rejected.

### D3. Synthesize `datasetRef.version = 1` literally

V1.22 step 2 inserts every backfilled dataset with `version = 1`. The snapshot was conceptually frozen "at run start time" but for pre-existing runs that time was before the dataset entity existed at all. The choice is between:

- *Literal `1`* — matches the dataset row that exists in the DB at backfill time. Honest to the post-migration state, even if the historical truth is "no dataset existed at the snapshot moment".
- *NULL* — leaves the field empty, signaling "no historical version".

We choose literal `1` because:

1. `DatasetReferenceDto` does not declare `version` as nullable; downstream consumers (DTO mapping, JSON serialization, equality checks in tests) treat it as present.
2. Filtering on `datasetRef.version` is not a current read path; nothing in the codebase compares snapshot's `datasetRef.version` against the live `dataset.version`. If such logic is added later (e.g., warn when re-running an old snapshot whose dataset has been edited), the comparison `snapshot.version=1` vs `live.version=N` is meaningful: it surfaces that the dataset has evolved since the (synthetic) "1".
3. The dataset row's actual `version` at backfill time is `1` (V1.22 step 2 INSERTs with `version = 1`). The synthesized snapshot value matches DB ground truth at backfill time, which is the simplest invariant to reason about.

*Alternative considered:* `NULL`. Forces every downstream consumer to defend against null; conflicts with the typed-DTO contract; gives no positive information.

### D4. Synthesize `datasetRef.name` as `'DATASET_' || ts.name`

Matches the format produced by V1.22 step 2 (`'DATASET_' || name`) for the backfilled dataset row. This ensures `snapshot.datasetRef.name` equals `dataset.name` at backfill time, which is the same identity invariant we get for a freshly-written v2 snapshot.

*Alternative considered:* keep raw `ts.name` (no prefix). Would diverge from the actual `datasets.name` value; any code comparing the two strings would treat them as different references. Rejected.

### D5. Guard the UPDATE with `(suite_snapshot -> 'datasetRef') IS NULL`

Idempotency: re-running the migration on a partially-migrated DB (e.g., interrupted Flyway run, manual re-apply against a dev box that already had some rows touched) skips already-migrated rows. The guard checks `(suite_snapshot -> 'datasetRef') IS NULL` rather than `snapshot_version IS NULL OR = '"1"'` because the absence of `datasetRef` is the actual signal we care about — a snapshot with `datasetRef` is already v2-shaped regardless of how `snapshotVersion` is set.

We use the `->` IS NULL form rather than the natural `?` key-existence operator (`NOT (suite_snapshot ? 'datasetRef')`) because JDBC `PreparedStatement` interprets `?` as a parameter placeholder. Flyway's own SQL executor does not bind parameters and would tolerate the `?` operator, but the functional test in tasks 3.4 re-executes the same SQL via `jOOQ DSLContext.execute(String)` which goes through JDBC PreparedStatement — and "No value specified for parameter 1" surfaces immediately. Switching to `(suite_snapshot -> 'datasetRef') IS NULL` keeps the SQL portable across both execution paths.

The two forms are semantically distinct only when the key exists with explicit JSON null as its value (in which case `?` returns true and `->` IS NULL also returns false; for the `NOT` form `NOT ?` is false and `IS NULL` is also false — actually they match here). The forms differ only on the explicit-null case if the value were a non-null JSON null — none of our data shape ever produces that, so the change is safe.

*Alternative considered:* unconditional UPDATE. Would overwrite the `datasetRef.version` of already-v2 snapshots with `1`, corrupting genuine v2 data. Rejected.
*Alternative considered:* use `jsonb_path_exists(suite_snapshot, '$.datasetRef')`. Function form is portable but more verbose; `->` IS NULL is both shorter and semantically equivalent for our data.

### D6. Use `jsonb_set(... CREATE_MISSING=true)` for both keys

`jsonb_set` with `CREATE_MISSING=true` either inserts a missing key or overwrites an existing one. Since we guard on the absence of `datasetRef`, the row we are UPDATEing definitely lacks `datasetRef`; for `snapshotVersion` the guard does not enforce absence (a row might have version with no datasetRef in some weird corruption case). Overwriting `snapshotVersion` to `"2"` in either case is safe — it's the value we want.

*Alternative considered:* `||` (JSONB concatenation operator). Less explicit about which keys are being changed; harder to read against a multi-key JSON.

### D7. Join via `test_suite_runs.test_suite_id = test_suites.id`

CASCADE FK on `test_suite_runs.test_suite_id` guarantees no orphan rows. The join is INNER (no LEFT JOIN needed) and the result set is exactly the set of runs whose snapshot needs backfilling. The `test_suites.id` PK supports the join cheaply.

*Alternative considered:* LEFT JOIN with a NULL-name fallback. Unnecessary given CASCADE; would only mask a bug in the FK.

### D8. Spec delta: align with post-backfill reality, not the original intent

The existing `suite-run-snapshot/spec.md` says `@Builder.Default = "1"` and v1 stays readable. The implementation says `@Builder.Default = "2"` and v1 is rejected. The original `introduce-dataset-entity` change diverged the code from the spec without updating the spec.

This change takes the position that the **implementation is correct** (after the backfill, no v1 rows exist; the version gate is defense-in-depth) and updates the spec text to match. Specifically:

- `SuiteSnapshotDto model` requirement is MODIFIED to declare `@Builder.Default = CURRENT_VERSION = "2"`, drop the "v1 remains readable" scenario, and add a "post-backfill, all stored snapshots have datasetRef populated" scenario.
- `API surface` requirement is MODIFIED to drop the "null datasetRef for legacy snapshots" scenario; all detail responses carry a populated `datasetRef`.
- A new ADDED requirement under the `introduce-dataset-entity` migration topic documents the one-time backfill outcome (post-migration invariant: every non-null `suite_snapshot` has `snapshotVersion = "2"` and a populated `datasetRef`).

The one-time SQL itself does not appear as a requirement (per the spec authoring rule: operational concerns belong in tasks.md / design.md, not specs). The *post-migration invariant* it produces is a functional requirement that future code can rely on.

### D9: Effect on baseline scenarios under `Requirement: SuiteSnapshotDto model`

The MODIFIED replacement drops three baseline scenarios:
- `Missing snapshotVersion defaults to "1"`
- `Snapshot persisted before this change with no snapshotVersion field is treated as v1`
- `Version-1 snapshots remain readable`

All three describe behavior that no longer exists post-backfill. The replacement scenario `Missing snapshotVersion defaults to CURRENT_VERSION` preserves defense-in-depth wording for the absent-key path (treated as a producer bug, not a routine code path). The `API surface` requirement similarly drops the baseline scenario `Detail response carries null datasetRef for legacy snapshots`, replaced by `Detail response includes datasetRef for snapshots backfilled by V1.22`.

## Risks / Trade-offs

- **Risk: a `suite_snapshot` row's JSONB is shaped unexpectedly (e.g., not a JSON object, but an array or scalar)** → `jsonb_set` on a non-object errors out at runtime and aborts the V1.22 transaction. Mitigation: this is fail-loud behavior; if such corruption exists, the migration refuses to apply and surfaces it explicitly. Practically, the column is JSONB-typed and was written by `objectMapper.writeValueAsString(SuiteSnapshotDto)` — which always produces an object — so this is theoretical. No additional guard added.
- **Risk: V1.22 transaction grows by one O(N) UPDATE over `test_suite_runs`** → Bounded by the number of historical runs. Join is on `test_suite_id → test_suites.id` (PK lookup). Cost is acceptable inside the existing V1.22 single-transaction envelope; no need to chunk.
- **Risk: synthesized `datasetRef.version = 1` diverges from the dataset's actual version after subsequent edits** → Acknowledged. The synthesized value reflects the dataset's state at backfill time, not at the original (pre-dataset-entity) run time. Any future code that wants to detect "snapshot is stale" by comparing snapshot.version vs live.version will treat backfilled snapshots as "version 1, dataset has since been edited (or not)". This is the natural and correct interpretation given that the snapshot's original meaning had no version concept.
- **Risk: the existing main spec text contradicts the implementation and now also contradicts this change's spec delta** → Resolved here by updating the main spec via MODIFIED requirements. After this change archives and `opsx:sync` runs, the main spec reflects the actual code+data shape.
- **Risk: someone re-runs V1.22 on a DB that already has v2 snapshots (e.g., a dev environment that wrote new runs after V1.22 was applied, then someone manually re-runs Flyway)** → Idempotency guard (`NOT (suite_snapshot ? 'datasetRef')`) skips v2 rows. Safe.

## Migration Plan

This change is itself the migration: it amends V1.22, which is the only deployment vehicle.

### Deployment sequence

1. Apply this change (edit V1.22 in place, update spec, add test).
2. `./gradlew test` — verifies the new functional test passes alongside the existing V1.22 test suite and `JooqSchemaDriftTest` (jOOQ generated sources are unchanged, so no regeneration is required).
3. `./gradlew clean build` — full build + checkstyle.
4. Merge to the feature branch; the branch continues to be the integration point for the dataset-entity work.
5. When the dataset-entity branch eventually deploys to staging/prod, V1.22 (now including the snapshot backfill) runs as a single Flyway step.

### Rollback strategy

- For unreleased environments (dev, CI): no rollback needed; just drop the database and re-apply.
- For staging/prod after deployment: same rollback strategy as the parent `introduce-dataset-entity` change — restore from the pre-migration backup. The amended V1.22 has no rollback file (Flyway forward migrations do not support automated rollback in this project).

### Code changes shipped together

- Only the SQL edit and the new functional test. No Java source changes, no jOOQ regeneration, no `docs/database-schema.md` update (schema is unchanged).

## Open Questions

None at this time. The decisions above are fully constrained by the existing V1.22 invariants and the WIP-branch context.
