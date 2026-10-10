## Context

See proposal.md — Why. Current state relevant to the approach:

- `datasets.test_case_schema` is a JSONB array of `FieldDefinitionDto` (class lives in `evaluation-runner-core`, `runner.dto`). It is the request and response shape, the run-snapshot shape (`SuiteSnapshotBuilder`), the ZIP manifest shape (`ZipManifest`), and what `eval-cli` reads (read-only — the CLI never writes a dataset).
- `DatasetService.update` loads with plain `findById` (optimistic version only), computes `computeRemovedFields` by name, saves, calls `TestCaseService.removeDataFields` (prunes `data` and well-formed `multi_turn_data` arrays), then `RevalidationService.startDatasetRevalidation`.
- Revalidation is synchronous in the caller's transaction: the `@Async` on `runDatasetRevalidationAsync` was bypassed by self-invocation. The annotation has been removed and the method renamed `runDatasetRevalidation` (already in the working tree; no behaviour change).
- `CsvImportService` writes the schema straight to `DatasetRepository.updateTestCaseSchema`, reads it via `DatasetSchemaProvider`, persists it **before** `fixupTestCases`, and triggers its own revalidation. ZIP import delegates to it.
- Every suite binding fires `fn_test_suites_private_binding_guard`, which takes `SELECT … FOR UPDATE` on the target `datasets` row for PUBLIC and PRIVATE targets alike.
- The "PRIVATE dataset ⇒ exactly one suite" invariant is enforced in the application layer (create-and-bind, clone refusal, suite-delete cascade, rebind refusal, visibility-transition precondition); the DB trigger only enforces "at most one". So a PRIVATE dataset always has exactly one suite to revalidate.

## Goals / Non-Goals

**Goals:**
- One place that resolves field ids and classifies a schema diff (renames vs removals), used by create, update, and — through `update` — CSV/ZIP import.
- Data move for renames in one SQL statement per dataset, simultaneous mapping semantics.
- `CsvImportService` depends on `DatasetService` only for dataset reads/writes; no new `DatasetService` method.

**Non-Goals:**
- Propagating a rename into suite configuration (template variables, bindings, `testCaseFilter`, JSONata, TSMD conditions). Suites see the rename through revalidation; that is exactly why shared datasets are guarded.
- Relocating values between `data` and `multiTurnData` on a `perTurn` flip.
- Field ids in CSV/ZIP exports, run-snapshot backfill, or a cross-dataset meaning for ids (clone copies the JSON verbatim, ids included — harmless, ids are per-schema).
- Making revalidation actually asynchronous again.
- A DB-level "PRIVATE ⇒ ≥1 suite" constraint.

## Decisions

### D1 — `id` on `FieldDefinitionDto`, nullable `String`, `@JsonInclude(NON_NULL)`
Added in `evaluation-runner-core` (plus `@Builder(toBuilder = true)` for D2's copies) so the snapshot, manifest, request, and response all share it. Nullable because it is optional on input and absent in historical snapshots. No explicit `@JsonInclude` needed (the shared mapper is `NON_NULL`). No `@Pattern`: an id is valid iff it belongs to the current schema, which bean validation cannot express; a non-UUID string simply fails the unknown-id check. OpenAPI: `description` = "Server-assigned field identity. Echo it back unchanged on update to preserve the field (including across a rename); omit it for new fields; never invent one."
*Alternative:* a separate `DatasetFieldDefinitionDto` in the main app with `id` only there. Rejected — the snapshot is meant to carry the id (user decision), and two near-identical DTOs would need mapping everywhere the schema flows.

### D2 — `DatasetSchemaFieldIdResolver` (`service.domain`, injectable `@Component`)
Pure function, no repository access, unit-tested in isolation.

```
SchemaFieldResolution resolveForCreate(List<FieldDefinitionDto> incoming)
SchemaFieldResolution resolveForUpdate(List<FieldDefinitionDto> current, List<FieldDefinitionDto> incoming)

record SchemaFieldResolution(List<FieldDefinitionDto> schema,      // incoming, every field carrying an id
                             Map<String, String> renames,          // oldName -> newName
                             List<String> removedNames)            // old names whose id is absent
```

Algorithm (update):
1. Reject case-insensitive duplicate incoming names → `ValidationException` (400). Not enforced anywhere today despite the existing spec; without it two entries renamed onto one name would make `jsonb_object_agg` keep only one value.
2. Reject duplicate incoming ids → 400; reject incoming ids not present in `current` → 400.
3. Bind explicit ids: each incoming entry with an id claims the current field with that id.
4. Name fallback: each incoming entry without an id claims the still-unclaimed current field with the exactly equal name (case-sensitive — data keys are case-sensitive). It takes that field's id, or a fresh one when the stored field has no id (a rollback leftover or an explicit `"id": null`) — either way it is the same field, not a removal.
5. Unmatched incoming entries get `UUID.randomUUID()`.
6. `renames` = claimed current fields whose name differs from the claiming entry; `removedNames` = current fields nobody claimed.

Create: any incoming id → 400; duplicate names → 400; all fields get fresh ids. Matching is over claimed **current fields** (not over ids), which is what makes stored id-less fields safe.

The resolver returns new `FieldDefinitionDto` instances (`toBuilder`-style copy) rather than mutating the request DTO.
*Alternative:* inline in `DatasetService`. Rejected per AGENTS.md (specialized components, not private methods) and because the swap/claim rules deserve focused unit tests.

### D3 — `DatasetService.update` flow (single meta transaction, no explicit lock)
Concurrency rests on the existing optimistic version (`PostgresDatasetRepository.save` updates `WHERE id = ? AND version = ?` and throws `OptimisticLockException` → 409 `VERSION_CONFLICT`) plus ordering, not on `SELECT … FOR UPDATE`:
1. `findById` and the version check, as today (version conflict is checked before any id validation, so a stale request gets 409 rather than an id 400).
2. `resolution = resolver.resolveForUpdate(currentSchema, normalized.testCaseSchema)`; replace the request schema with `resolution.schema()`.
3. `schemaChanged` = stored JSON vs `resolution.schema()` JSON (semantic compare, as today) — an id-less client resending an unchanged schema is not a change.
4. mapper update + **save**. The save's UPDATE takes the dataset row lock (held to commit) and fails on a stale version.
5. Rename guard, **after** the save: if `!renames.isEmpty()` and the dataset is `PUBLIC` and `testSuiteService.countReferencingDataset(id) > 0` → throw `DatasetVisibilityRuleException(DATASET_FIELD_RENAME_FORBIDDEN)`; the transaction rolls back, save included. Why after: binding a suite does not bump the dataset version, so a pre-save check could race a bind. The binding trigger takes `FOR UPDATE` on the same row, so once our save holds it, any in-flight bind has committed (and is counted — READ COMMITTED, new statement) and any later bind waits for our commit. Applies even with zero test cases (the guard is about suites, not data).
6. `testCaseService.removeDataFields(id, removedNames)` **then** `testCaseService.renameDataFields(id, renames)`. Order matters: a removed field's old name may be a rename target (`{id:A,a}→{id:A,b}` while old `b` is dropped); pruning first leaves `b` holding `a`'s values. Rename sources and removed names are disjoint by construction.
7. If `schemaChanged`: invalidate schema cache, run revalidation (synchronous; refreshes every referencing suite — for a PRIVATE dataset its single suite — before returning).

Lock order is therefore always **dataset row → test-case rows**; D6 makes the import follow the same order, so the two cannot deadlock.

`create` calls `resolver.resolveForCreate` before mapping.

### D4 — Rename error code
New `DatasetVisibilityErrorCode.DATASET_FIELD_RENAME_FORBIDDEN` → HTTP 409, new wire `ErrorCode.DATASET_FIELD_RENAME_FORBIDDEN`, mapped in `DefaultExceptionHandler.visibilityStatusFor` / `toWireErrorCode` (both exhaustive switches, so the compiler forces the mapping). Message names the dataset and the number of bound suites.
*Alternative:* `InvalidOperationException`. Rejected — the visibility-rule family already models "allowed only for PRIVATE / unbound" conflicts with distinct wire codes the FE can branch on.

### D5 — `TestCaseRepository.renameDataFields(UUID datasetId, Map<String, String> renames)`
One `UPDATE test_cases … WHERE dataset_id = ?` built like `removeDataFields` (jOOQ with bound `ARRAY[?, …]` parameters, never inlined names). For an object `o` and parallel arrays `old[]`, `new[]`:

```
(o - old[]) || COALESCE((SELECT jsonb_object_agg(m.dst, o -> m.src)
                         FROM unnest(old[], new[]) AS m(src, dst)
                         WHERE o ? m.src), '{}'::jsonb)
```

(`o` stands for the column expression — `data`, or the `multi_turn_data` element; alias columns must not shadow it.) Every new key is read from the **original** object, so swaps and chains are simultaneous; `WHERE o ? m.src` copies only present keys (a stored JSON `null` value is preserved as `null`, an absent key stays absent). Applied to `data`, and element-wise to `multi_turn_data` under the same shape guard as `removeDataFields` (only when it is a JSONB array of objects; every other shape untouched). The UPDATE is restricted to rows that hold an old key (`data ?| old[]` or a `multi_turn_data` element that does), unlike `removeDataFields`, which rewrites every row of the dataset. `updated_at_ms` is not bumped, matching `removeDataFields`. `TestCaseService.renameDataFields` is a thin pass-through, keeping `DatasetService` off `TestCaseRepository`.

### D6 — CSV/ZIP import goes through `DatasetService`
`CsvImportService` replaces `DatasetRepository` + `DatasetSchemaProvider` with `DatasetService`, which gains exactly one method:

```
DatasetResponseDto getByIdForUpdate(UUID id)   // findByIdForUpdate + toDto; meta tx (MANDATORY propagation — only meaningful inside the caller's tx)
```

- **lock first:** `importCsv` calls `datasetService.getByIdForUpdate(datasetId)` before any test-case write (before OVERRIDE's `deleteAllByDatasetId` and the first batch insert). This replaces the current unlocked `findById` + `datasetSchemaProvider.getSchema` (`CsvImportService.java:304,314`). Effects: (a) lock order dataset row → test-case rows, matching D3, so import vs PUT cannot deadlock; (b) the import works from committed state that cannot change underneath it — no stale schema (MERGE re-persists `current + delta`, which would otherwise carry pre-rename ids and be read as a rename back), no stale `name`/`description`; (c) a concurrent import into the same dataset waits. A PUT that read the dataset before the import committed fails its save with 409 `VERSION_CONFLICT`. The explicit `expectedVersion` check stays as is.
- **persist:** build a `DatasetRequestDto` from the locked read (`name`, `description`, `testCaseSchema = newSchema`, `createdBy = null`; `visibility` is ignored on update) and call `datasetService.update(datasetId, request, lockedVersion)`. `update` re-reads under our own lock, so the version always matches.
- **ids:** MERGE passes current definitions through (ids included) and appends new ones. OVERRIDE / empty schema (`CsvSchemaFieldBuilder.buildFromBindings`) builds new field objects with only `name`, `type`, `required=false`, `perTurn` — surviving columns keep their id through D2's name fallback; as today they lose `displayName`, `description`, `required` (unchanged behaviour, out of scope). Imports never produce renames, so the D3 rename guard never fires.
- **ordering:** `persistSchema` moves **after** `fixupTestCases` (the fixup uses the in-memory `finalSchema`, not the stored one), so the revalidation `update` runs sees coerced rows.
- **revalidation:** the explicit `revalidationService.startDatasetRevalidation` call is removed — `update` runs it only when the resolved schema differs from the stored one. An OVERRIDE that reproduces the current schema now records no task (accepted: suite validity depends on config, not rows, and rows are validated inline during the import). Side benefit: the schema cache is now invalidated on import (previously it was not).
- `update`'s `removeDataFields` is a no-op in practice (OVERRIDE deleted all rows first; MERGE/APPEND only add fields).

No dependency cycle: `DatasetService` → `TestCaseService` → (no CSV); `ZipImportService` already depends on `DatasetService`.
*Alternatives:* (1) a new `DatasetService.replaceTestCaseSchema` — rejected, reuse `update`. (2) Optimistic version only, no import lock — leaves the import-vs-PUT deadlock (opposite lock orders; Postgres would abort one with `40P01`). (3) Two-pass import (infer schema, save, then insert) to save the dataset first — needs the upload buffered to a temp file and doubles parsing; rejected. (4) A no-op `update` at import start to take the lock — double version bump; rejected.

### D7 — ZIP manifest strips ids both ways
`ZipManifestSerializer.write` nulls `id` on every field (so `NON_NULL` omits it); `read` nulls any `id` it parses. Without the read-side strip, a manifest exported from another dataset (or by a build between deploys) would carry foreign ids and fail the unknown-id check in `update`. CSV export never serialized the schema, so nothing changes there.

### D8 — Backfill `V1.36__AddIdsToDatasetSchemaFields.sql`
Pure data, SQL migration (no DDL → no `generateJooq`, no drift-test impact):

```sql
UPDATE datasets d
SET test_case_schema = (
    SELECT jsonb_agg(CASE WHEN jsonb_typeof(e.elem) = 'object'
                               AND jsonb_typeof(e.elem -> 'id') IS DISTINCT FROM 'string'
                          THEN e.elem || jsonb_build_object('id', gen_random_uuid()::text)
                          ELSE e.elem END
                     ORDER BY e.ord)
    FROM jsonb_array_elements(d.test_case_schema) WITH ORDINALITY AS e(elem, ord))
WHERE CASE WHEN jsonb_typeof(d.test_case_schema) = 'array'
           THEN EXISTS (SELECT 1 FROM jsonb_array_elements(d.test_case_schema) x
                        WHERE jsonb_typeof(x) = 'object'
                          AND jsonb_typeof(x -> 'id') IS DISTINCT FROM 'string')
           ELSE false END;
```

Idempotent (only fills missing or `null` ids on object elements, passes non-object elements through unchanged, preserves order). The `WHERE` uses `CASE` because Postgres does not guarantee `AND` short-circuiting — `jsonb_array_elements` must never run on a non-array value. Does not bump `version`/`updated_at_ms` — it is an additive data fix, and a client holding an id-less copy still resolves by name. `test_suite_runs` snapshots untouched. `gen_random_uuid()` is core in PostgreSQL 13+.

## Risks / Trade-offs

- [Revalidation is synchronous inside the PUT/import transaction while the dataset row lock is held (by the save, or by the import's lock-first read)] → Long PUTs/imports on very large datasets block concurrent binds/PUTs/imports of the same dataset for their duration. The work itself was already synchronous; the lock only adds serialization on one row. Making revalidation genuinely async is a separate change.
- [Response still reports the task as `PENDING` although the task row is already terminal] → Kept deliberately to avoid an FE contract change; the spec scenario is unchanged. The FE can re-fetch the task.
- [Old clients that drop `id`] → Fall back to name matching: no regression, but renames from them remain delete + create. FE must round-trip ids.
- [A rename invalidates the PRIVATE dataset's own suite (templates/bindings still use the old name)] → Accepted: the owner renamed it; revalidation flags the suite in the same response. No auto-rewrite of suite config (non-goal).
- [Client sends `id` + `name` of a field that another request entry also claims by name] → Explicit ids win; name fallback only takes unclaimed ids (D2 step 3).
- [`FieldDefinitionDto` change ripples into `evaluation-runner-core` / `eval-cli`] → Additive nullable field; CLI only reads schemas; `RunnerModuleConstraintsTest` unaffected.

## Migration Plan

1. Deploy backend: Flyway runs V1.36 on start; every dataset schema field gets an id. Responses start returning `id`.
2. FE ships round-tripping `id` (until then FE behaves exactly as today via name matching).
3. Rollback: no reverse migration needed — the shared `JsonMapper` disables `FAIL_ON_UNKNOWN_PROPERTIES` (`JsonMapperConfiguration`), so the previous build ignores the `id` key. Writes made by the previous build drop ids; after rolling forward again, id-less fields resolve by name on the next write (D2), so a second backfill is unnecessary.
