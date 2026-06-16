## Why

Stored `test_suite_runs.suite_snapshot` JSONB blobs written before V1.22 carry `snapshotVersion = "1"` (explicit, written by the pre-dataset producer) and no `datasetRef` key. Under the post-V1.22 application code, `resolveSnapshot` throws `UnsupportedSnapshotVersionException` on every such row, making historical runs unexecutable and forcing every read path that touches `snapshot.getDatasetRef()` to defend against the v1 case. V1.22 is still on a WIP branch and has not been deployed. The cheapest fix is to add a one-shot JSONB `UPDATE` step to V1.22 itself so the migration leaves every stored snapshot in a uniform v2 shape — `snapshotVersion` rewritten to `"2"` and `datasetRef` synthesized from the joined `test_suites` row (using the V1.22 invariant `dataset.id = source_suite.id`).

The mapping is deterministic by D1 (`dataset.id = source_suite.id`) and CASCADE-safe (`test_suite_runs.test_suite_id` is `ON DELETE CASCADE`, so no orphan run rows survive to hit a missing suite).

## What Changes

- Add a new step to `V1.22__IntroduceDataset.sql` that updates every `test_suite_runs` row with a non-null `suite_snapshot` missing the `datasetRef` key:
  - sets `snapshotVersion` to `"2"` (idempotent via `jsonb_set ... CREATE_MISSING=true`)
  - sets `datasetRef` to `{ id: ts.id, version: 1, name: 'DATASET_' || ts.name }` joining `test_suites ts ON r.test_suite_id = ts.id`
- Ordering inside V1.22: the step runs **after** step 2 (datasets table populated) so the `version`/`name` values align with the backfilled dataset row, but **before** any other JSONB mutation that depends on the snapshot shape (there are none in V1.22 itself, so end of the file is fine).
- Add a functional test that stores a pre-V1.22-shaped `suite_snapshot` (JSON with `snapshotVersion = "1"` and no `datasetRef`, matching the actual pre-V1.22 producer output — the original `SuiteSnapshotDto` declared `@Builder.Default private String snapshotVersion = "1"`, which Jackson serializes unconditionally), runs the V1.22 backfill SQL against it, and asserts that `resolveSnapshot` returns a snapshot with `snapshotVersion = "2"` and `datasetRef.id` populated and equal to the originating suite's id.
- No production Java code changes. The existing `UnsupportedSnapshotVersionException` rejection path becomes effectively unreachable in any deployed environment after the backfill runs; it stays in place as defense-in-depth against future producer bugs and forward-compatibility for a future v3.

This change is non-breaking: V1.22 is unreleased, so no environment has applied the prior version of the file.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `suite-run-snapshot`: the snapshot-resolution spec gains a requirement that legacy snapshots (pre-V1.22, carrying `snapshotVersion = "1"` and no `datasetRef`) are normalized to v2 shape during the `introduce-dataset-entity` Flyway migration, so the `resolveSnapshot` reader sees a uniform shape regardless of when the snapshot was written. The existing "legacy fallback synthesizes via live dataset" requirement (for `suite_snapshot IS NULL`) is unaffected.

## Impact

- **Migrations**: `src/main/resources/db/migration/meta/POSTGRES/V1.22__IntroduceDataset.sql` gains one new SQL block. No new Flyway file. Generated jOOQ sources are unchanged (no DDL).
- **Code**: none in `src/main/java`. The backfill is pure SQL.
- **Tests**: one new functional test under the suite-run-snapshot test class (or equivalent), exercising the legacy-snapshot path through the migrated DB.
- **Docs**: `docs/database-schema.md` is unchanged (no schema diff). `AGENTS.md` already documents the snapshot-version policy; the surrounding language can stay, though the comment "Genuine v1 snapshots set the field explicitly to `'1'`" becomes slightly more accurate (post-backfill, no row exists in either v1 or undefined shape).
- **Operational**: the V1.22 transaction grows by one `UPDATE … FROM test_suites` over `test_suite_runs`. Cost is bounded by the number of historical runs; the join key is the existing PK on `test_suites.id`. Acceptable inside the V1.22 single-transaction envelope.
- **Risk surface**: confined to the JSONB UPDATE. If any `suite_snapshot` value is somehow malformed (non-JSONB content in a JSONB column — Postgres-impossible by definition), the UPDATE would fail and abort the whole migration, which is the correct fail-loud behavior.
