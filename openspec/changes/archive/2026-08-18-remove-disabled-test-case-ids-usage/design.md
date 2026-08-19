## Context

See proposal.md — Why. The mechanics that shape this design:

- `test_suites.disabled_test_case_ids` is `JSONB NOT NULL DEFAULT '[]'::jsonb` (`V1.22__IntroduceDataset.sql`),
  backfilled from the dropped per-case `test_cases.is_enabled` column.
- The value is read at exactly one place per flow and deserialized by **three** independent private
  `deserializeDisabledIds` copies (`TestSuiteRunService`, `TestSuiteEvaluationJob`,
  `TestSuiteMapper`), then threaded downward as `Collection<UUID> excludedIds` through
  `RunnableTestCaseSelector` into `TestCaseRepository`, where it becomes
  `NOT (test_cases.id = ANY(?::text[]))`.
- It is written at four places, all column-enumerated jOOQ builders in `PostgresTestSuiteRepository`
  (`create`, `update`, `createWithId`, `updateDatasetId`). There is no `INSERT … SELECT` for `test_suites`,
  and suite clone builds its entity field-by-field via `TestSuiteMapper.toCloneEntity`.
- `SuiteSnapshotDto` never captured the list, so no persisted snapshot JSON changes and no snapshot version
  bump is involved.
- `JsonMapperConfiguration` disables `FAIL_ON_UNKNOWN_PROPERTIES`, so removing an inbound DTO field
  degrades to "silently ignored" rather than HTTP 400.
- `TestSuitesSchemaProvider` derives the `test_suites` query-DSL entity schema from generated jOOQ, so the
  public `/queries/execute` schema tracks the physical column, not the Java model.

## Goals / Non-Goals

**Goals:**
- One selection rule (`is_valid AND testCaseFilter`) reachable from one code path, so the run-creation
  count and the snapshot phase cannot diverge from each other again. (The raw `test_cases` query-DSL surface
  applies neither `is_valid` nor the ALL-turns-match quantifier and is out of scope here — see Risks.)
- Remove the mechanism, not just its current caller — no dormant parameters or helpers that a future change
  could re-wire.
- Keep the schema untouched so this ships without a migration, and keep the DB divergence (column present,
  model absent) explicit and documented rather than incidental.

**Non-Goals:**
- Data hygiene for the retained column. Its content is not read, not cleared, not migrated.
- Any change to `testCaseFilter` translation, multi-turn ALL-turns semantics, or `isValid`.
- Deprecation windows or compatibility shims for the removed API fields.

## Decisions

### 1. Signature surgery over passing empty collections

`excludedIds` is removed from `RunnableTestCaseSelector.countRunnable` / `loadRunnablePage`, from the four
`TestCaseRepository` methods (`…ExcludingIds{,Matching}` → `findValidByDatasetId{,Matching}` /
`countValidByDatasetId{,Matching}`), and from the private condition builder
(`validNotExcludedCondition` → `validCondition`).

*Alternative considered:* keep every signature and pass `List.of()` at the three call sites — a ~5-line
diff with no behavioral risk. Rejected: it leaves a fully wired exclusion path (including the `text[] = ANY`
predicate and its plan-cache justification) reachable from four public repository methods, which is exactly
the state that let two selection semantics coexist unnoticed. The cost of removal is mechanical; the cost of
dormancy is another #151.

### 2. `RunnableTestCaseCounter` is deleted, not emptied

Its whole body was `disabled != null ? disabled : List.of()`. Without that it is a one-line delegate to
`RunnableTestCaseSelector.countRunnable`, so `TestSuiteRunService` calls the selector directly.

*Alternative considered:* keep it as a named seam for the run guard. Rejected — the selector interface is
already the stable `service`-layer seam that inverts the dependency on `experimental.query`; a second
pass-through in front of it adds a hop without adding a boundary. This does mean `TestSuiteRunService`
gains a direct `RunnableTestCaseSelector` dependency, which `TestSuiteService` already has.

### 3. `DatasetCloneService.cloneRowAndTestCases` becomes `void`

Its `Map<UUID, UUID>` return existed only to feed `TestSuiteMapper.remapDisabledIds`. `DatasetService`
already discarded it; after this change all three call sites would. Returning it "just in case" would keep
building a map with one entry per copied test case, held for the whole clone transaction — an unbounded
in-memory structure in a paginated bulk copy, contrary to the project's bulk-operation rule.

*Alternative considered:* keep the return type and let callers ignore it. Rejected on the memory argument;
a future caller that genuinely needs id correspondence can reintroduce a streaming variant.

### 4. Column retained, code-blind, content unmanaged

No code reads or writes the column. Inserts omit it and rely on the DDL `DEFAULT '[]'::jsonb`; updates leave
existing arrays untouched, so legacy rows keep frozen values that nothing consults.

*Alternatives considered:*
- *Drop the column in this change.* Rejected as scoped out — it forces `generateJooq` regeneration, a
  `java-generated` diff, and edits to `JooqTableSchemaResolverTest` / `DatasetMigrationFunctionalTests` into
  a change whose point is code removal. Deferred to a follow-up (proposal.md — Deferred).
- *Write `'[]'` on every insert/update so rows self-heal.* Rejected: it keeps the column name in production
  code for a value nobody reads, and the healing is worthless because the column is going away. The
  trade-off accepted instead is that the column and the model disagree for one release.

### 5. Hard removal of the API fields rather than accept-and-ignore

`disabledTestCaseIds` leaves `TestSuiteRequestDto` and `TestSuiteResponseDto` outright. Because
`FAIL_ON_UNKNOWN_PROPERTIES` is off, an FE that still sends it gets HTTP 200 and no effect — no coordinated
release needed.

*Alternative considered:* keep the response field pinned to `[]` and `@Deprecated`. Rejected: a field that
is always empty invites clients to infer meaning from it ("nothing is excluded, so run conditions must be
inactive"), and it would have to be threaded through the model or synthesized in the mapper — reintroducing
the concept the change exists to delete.

### 6. Regression coverage seeds the column through a test back door

With no production writer left, the #151 regression test cannot set up its own precondition through the API.
It seeds `test_suites.disabled_test_case_ids` with raw SQL confined to `MetaTestDataHelper` (the existing
`appendDisabledTestCaseIds` helper is deleted; the regression test gets a purpose-named helper such as
`forceLegacyDisabledTestCaseIds`, matching the `forceSuiteInvalid` convention for back-door state), then
asserts that run creation and the snapshot ignore it.

*Alternative considered:* no regression test, on the grounds that the code path is gone. Rejected: the point
under test is that a *stored* value has no effect, which only a seeded row can demonstrate, and it is the
one test that would fail if someone reintroduced the read.

## Risks / Trade-offs

- **Retained column advertised by the query DSL** → `POST /api/v1/queries/execute` keeps listing
  `disabled_test_case_ids` as a filterable `ARRAY` field on `test_suites` for one release. Accepted;
  documented in `docs/database-schema.md` and closed by the follow-up change.
- **Model/schema divergence invites confusion** → a reviewer may read the column as live state. Mitigated by
  the `docs/database-schema.md` note, the delta-spec implementation notes, and the `dataset-entity` pattern
  doc edit.
- **Insert paths now depend on a DDL default** → if the column were ever recreated without
  `DEFAULT '[]'::jsonb`, suite inserts would fail `NOT NULL`. Mitigated: the column is `NOT NULL DEFAULT`
  today, functional create/clone tests exercise both insert paths, and the follow-up drops the column
  entirely.
- **UI-count vs run-count divergence is narrowed, not closed** → this change makes the run-creation count
  and the snapshot agree, but the FE's preview path (`POST /api/v1/queries/execute` on `test_cases`) applies
  neither `is_valid` nor the ALL-turns-match quantifier, so an invalid or partially-matching case can still
  be previewed and not run. Out of scope here (no code in this change touches that resolver); worth its own
  issue alongside the column drop.
- **Larger runs for affected suites** → intended correction; see proposal.md — Risks.
- **Three deserialize helpers deleted at once** → any missed caller is a compile error, not a runtime
  surprise, because the model field disappears with them.

## Migration Plan

No Flyway migration, no config property, no backfill, no feature flag. Behavior changes on deploy: the first
run created after the deploy for a suite carrying stale exclusions executes its full valid,
`testCaseFilter`-matching set. Rollback is a plain revert — the column and its data are still present, and
any suite row written while the new code was live simply carries `'[]'`, which the reverted code reads as
"nothing excluded" (the pre-change behavior for a suite whose exclusions were cleared).

Sequencing note for the follow-up: the column may only be dropped after this change is deployed, since the
reverted code would read it.
