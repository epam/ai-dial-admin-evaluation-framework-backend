## Why

GH #151: a suite created before run conditions existed (`Rag_eval_alps2` — 4 of 39 cases manually excluded
at the time) shows all 39 test cases as "Included" in the UI and "Selected test cases: 39 of 39" in the Run
Evaluation modal, but executes only 4. Applying a run condition makes it worse: the modal shows
"Selected test cases: 1 of 39" while `POST /runs` fails with 409 "Suite has no valid and enabled test cases".

Root cause is a **second, independent selection predicate**. Run selection is
`is_valid = TRUE AND NOT (id = ANY(disabled_test_case_ids)) AND testCaseFilter`, while the surface the FE
counts against (the `test_cases` Structured Query DSL entity) never knew about `disabled_test_case_ids` —
it only ever applied validity + filter. `disabledTestCaseIds` is the pre-run-conditions exclusion mechanism;
the FE no longer writes it, so the values left in the column are frozen artifacts of the old UI that silently
subtract from every run. Both symptoms fall out of that one extra `AND`: 4 executed instead of 39, and
`filter ∧ ¬disabled = ∅` → 409.

Run conditions (`testCaseFilter`, `suite-test-case-filter` spec) are the supported way to narrow a run and
fully subsume manual exclusion. Keeping a second mechanism alive means keeping two selection semantics that
provably disagree.

### Goals

- Run selection is defined by exactly one rule — `is_valid = TRUE AND testCaseFilter` — identical at the
  run-creation zero-runnable guard, at snapshot materialization, and on the query-DSL preview surface the FE
  counts against.
- Every code-level trace of `disabledTestCaseIds` is gone: model, mappers, request/response DTOs, selector
  and repository signatures, clone/detach remapping, validation constant, tests, docs.

### Non-goals

- **No DB change.** `test_suites.disabled_test_case_ids` stays exactly as it is, stale content included.
  Dropping it is a separate follow-up change (see "Deferred").
- No replacement per-test-case exclusion feature. Excluding cases is expressed as a run condition.
- No change to `testCaseFilter` semantics, to the ALL-turns-match rule for multi-turn cases, or to
  `isValid` (suite validity remains config-only).

## What Changes

- **Selection** drops the exclusion term everywhere. `TestSuiteRunService.createRun`'s runnable count and
  `TestSuiteEvaluationJob`'s snapshot paging both stop reading the column; all three private copies of
  `deserializeDisabledIds` (`TestSuiteRunService`, `TestSuiteEvaluationJob`, `TestSuiteMapper`) are deleted.
- **Signature surgery, not `List.of()` sprinkling.** `RunnableTestCaseSelector.countRunnable` /
  `loadRunnablePage` lose their `excludedIds` parameter; `TestCaseRepository.findValidByDatasetIdExcludingIds{,Matching}`
  and `countValidByDatasetIdExcludingIds{,Matching}` become `findValidByDatasetId{,Matching}` /
  `countValidByDatasetId{,Matching}`; `validNotExcludedCondition` becomes `validCondition(datasetId)` and its
  `NOT (test_cases.id = ANY(?::text[]))` array-binding trick disappears with the plan-cache javadoc that
  justified it. Leaving the parameters in place and passing empty lists would keep the whole exclusion
  machinery available for accidental re-wiring.
- **`RunnableTestCaseCounter` is deleted.** Its entire body was a null-to-empty-list coalesce for the
  exclusion argument; without it the class is a pass-through, so `TestSuiteRunService` calls
  `RunnableTestCaseSelector.countRunnable` directly.
- **`DatasetCloneService.cloneRowAndTestCases` becomes `void`.** Its `Map<UUID, UUID>` return existed solely
  to feed `TestSuiteMapper.remapDisabledIds`; `DatasetService` already ignored it, and after this all three
  call sites do. Dropping the return also drops an unbounded in-memory `HashMap` holding one entry per
  cloned test case for the whole clone transaction — aligned with the project's no-full-dataset-in-memory
  rule for bulk operations.
- **Clone and detach stop remapping.** The remap blocks in `TestSuiteCloneService` and
  `TestSuiteService.detachDataset` are removed, and `TestSuiteRepository.updateDatasetId` loses its
  `disabledTestCaseIds` parameter.
- **API (BREAKING, tolerated):** `disabledTestCaseIds` is removed from `TestSuiteRequestDto` and from
  `TestSuiteResponseDto` (in `evaluation-runner-core`, so `eval-cli` sees it too), along with
  `ValidationConstants.MAX_DISABLED_TC_IDS` and the OpenAPI schema/example references. A client that still
  sends the field is silently ignored rather than rejected — `JsonMapperConfiguration` disables
  `FAIL_ON_UNKNOWN_PROPERTIES` — so no coordinated FE release is required, though the FE should drop the
  field from its payloads.
- **BREAKING (behavioral, intended):** a legacy suite carrying stale exclusions now runs every valid test
  case matching its `testCaseFilter`, so run sizes for such suites grow to what the UI already reports.
  This is the correction #151 asks for.
- No new packages, no new classes, no config properties, no Flyway migration.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `test-suites`: removes the `Per-suite disabledTestCaseIds` requirement wholesale (field, `[]` default,
  10000-entry cap, UUID-format validation, stale-id tolerance, "disabled test cases excluded from runs"),
  and drops the field from the TestSuite entity description, the create/update request contract, and the
  zero-runnable-test-cases scenario.
- `test-suite-runs`: the run-time presence check counts test cases that are valid and (when set) match
  `testCaseFilter` — the `disabledTestCaseIds` term leaves the requirement, the `numberOfTestCases` snapshot
  scenario, and the zero-runnable 409 scenario.
- `suite-run-snapshot`: snapshot paging selects valid + `testCaseFilter`-matching cases only; the
  "Snapshot excludes disabled test cases" and stale-disabled-id scenarios are removed.
- `suite-test-case-filter`: the runnable definition becomes `is_valid = true AND testCaseFilter`; the
  no-filter fallback becomes "validity only", and the scenarios stop seeding `disabledTestCaseIds`.
- `test-suite-clone`: cloned suites no longer inherit or remap `disabledTestCaseIds`, and it leaves the
  overridable-fields list.
- `detach-dataset`: detach no longer remaps exclusions; `updateDatasetId` rebinds `dataset_id` only.
- `test-cases`: the pointers that redirect the removed per-case `enabled` flag to the suite's
  `disabledTestCaseIds` are re-pointed at `testCaseFilter` (`suite-test-case-filter`), and the field leaves
  the mutable-suite-fields list.
- `openspec/specs/README.md`: four entries (`test-suites`, `suite-test-case-filter`, `test-suite-clone`,
  `detach-dataset`) describe the field in their summaries and become materially inaccurate — update per the
  Spec Index Maintenance Policy.

## Impact

### Code

- Model / persistence: `data/db/model/TestSuite.java` (field), `data/db/mapper/TestSuiteRecordMapper.java`
  (single read point), `data/db/repository/PostgresTestSuiteRepository.java` (three
  `.set(DISABLED_TEST_CASE_IDS, …)` sites in `create`/`update`/`createWithId`, plus `updateDatasetId`),
  `data/db/repository/TestSuiteRepository.java`, `data/db/repository/TestCaseRepository.java` +
  `PostgresTestCaseRepository.java`.
- Selection: `service/domain/TestSuiteRunService.java`, `service/domain/job/TestSuiteEvaluationJob.java`,
  `service/domain/job/InProcessEvaluationExecutor.java` (legacy fallback already passed an empty list —
  drops the argument and the comment explaining why it was empty),
  `service/domain/job/RunnableTestCaseSelector.java`,
  `experimental/query/service/QueryDslRunnableTestCaseSelector.java`.
  Deleted: `service/domain/RunnableTestCaseCounter.java`.
- Clone / detach: `service/domain/TestSuiteCloneService.java`, `service/domain/TestSuiteService.java`,
  `service/domain/DatasetCloneService.java`, `service/domain/mapper/TestSuiteMapper.java` (five mapping
  sites + `serializeDisabledIds` / `deserializeDisabledIds` / `remapDisabledIds`).
- API: `service/domain/dto/TestSuiteRequestDto.java`, `constants/ValidationConstants.java`,
  `web/controller/TestSuiteController.java` (clone-endpoint description), `evaluation-runner-core`'s
  `runner/dto/TestSuiteResponseDto.java`, and the `testCaseFilter` descriptions in both DTOs that currently
  say the filter is "combined with `disabledTestCaseIds`".
- OpenAPI examples: ~17 JSON files under `src/main/resources/openapi/examples/` (test-suite POST/PUT/GET/clone
  requests and responses).

### Data

- `test_suites.disabled_test_case_ids` (`JSONB NOT NULL DEFAULT '[]'`) is untouched. No code writes it, so
  inserts fall back to the column DEFAULT and updates leave existing arrays alone — stale content is
  explicitly don't-care because the column is scheduled for removal.
- Stale values do **not** propagate to clones: both `test_suites` insert paths are column-enumerated jOOQ
  builders (no `INSERT … SELECT`) and suite clone goes through the field-by-field `TestSuiteMapper.toCloneEntity`,
  so cloned rows get `'[]'` from the DEFAULT.
- The generated jOOQ `TestSuitesRecord.getDisabledTestCaseIds()` remains — it is simply never called.

### Tests

- Deleted: `RunnableTestCaseCounterTest`; `LargeDatasetSnapshotFunctionalTests` (its sole purpose is proving
  the 10000-element `text[] = ANY` predicate keeps index seeks — the predicate is gone);
  `SuiteSnapshotFunctionalTests` "snapshot excludes disabled test cases";
  `DatasetDetachFunctionalTests.shouldRemapDisabledTestCaseIdsToClonedIds`;
  `TestSuiteDatasetFunctionalTests.disabledTestCaseIdsRoundTrip`; the `TestSuiteCloneFunctionalTests` remap
  assertions (the surrounding cross-pagination-boundary clone test stays).
- Test helpers: `MetaTestDataHelper.appendDisabledTestCaseIds` and the `!enabled` branch of
  `seedManyTestCases` (both raw-SQL JSONB appends) are removed, and the vestigial `enabled` flag leaves
  `seedManyTestCases` and `BaseTestCaseBulkPatchFunctionalTests.createTestCase`. **No caller passes
  `enabled=false`**, so no fixture needs a replacement.
- Reworked: `DatasetCloneServiceTest` asserts the clone via captured inserts (new ids, repointed
  `datasetId`, `@ef/datasets/` ref rewrite) now that the method returns `void`; `TestSuiteMapperCloneTest`,
  `TestSuiteServiceTest`, `SuiteSnapshotBuilderTest`, `TryItOutServiceTest`, `TestSuiteEvaluationJobTest`,
  `TestSuiteCloneServiceTest`, `InProcessEvaluationExecutorTest` drop the field from builders and stubs.
- Added (must fail before the change): a functional regression for #151 — a suite whose
  `disabled_test_case_ids` is seeded through a raw-SQL back door (no production code writes it any more)
  plus a `testCaseFilter`; assert the run-creation count and the materialized `test_case_run_inputs` include
  every filter-matching valid case, and that a filter selecting only stale-listed cases yields a successful
  run instead of 409.

### Docs

`docs/patterns/dataset-entity.md` (the field is one of its three headline concepts),
`docs/database-schema.md` (mark the column as retained-but-unused by code, removal pending),
`AGENTS.md` (the Unique Patterns row for `Dataset Entity` names `disabledTestCaseIds` in its "why it
matters" cell), and `openspec/specs/README.md` (four capability summaries describe the field). Two
non-requirement spec passages that delta specs cannot carry — the `test-suites` Key Terms bullet and the
`test-cases` Purpose paragraph — are edited during `/opsx:sync`. No `docs/configuration.md` change (no
properties) and no `openspec/config.yaml` change (this removes a feature within the existing architecture
rather than changing a rule).

## Risks

- **Larger runs for affected suites.** Suites with stale exclusions will execute more test cases — more
  deployment calls, more metric evaluations, higher cost per run. This is the intended correction and
  matches what the UI already promises; no mitigation beyond release-note visibility.
- **Silent request-field drop.** With `FAIL_ON_UNKNOWN_PROPERTIES` disabled, a client PUTting
  `disabledTestCaseIds` gets HTTP 200 and no effect. Preferable to a hard 400 for a field the FE is
  expected to stop sending, but it must be called out in the release notes so nobody debugs a no-op.
- **Vestigial field on the query-DSL surface.** `TestSuitesSchemaProvider` derives the `test_suites` entity
  schema from generated jOOQ, so `POST /api/v1/queries/execute` keeps advertising `disabled_test_case_ids`
  as a filterable `ARRAY` field (and `JooqTableSchemaResolverTest` keeps asserting it) until the column is
  dropped. Accepted as the price of deferring the DB work.
- **Insert paths rely on the column DEFAULT.** Verified: `V1.22__IntroduceDataset.sql` declares
  `disabled_test_case_ids JSONB NOT NULL DEFAULT '[]'::jsonb`, and both inserts enumerate columns
  explicitly, so omitting it cannot violate `NOT NULL`. A functional create/clone test guards this.

## Rollout

Single PR, no migration, no config property, no feature flag, no data backfill — behavior changes the moment
the build deploys. Reversible by revert; the column and its data are still there.

## Deferred (separate change)

Drop the column: `V{n}__DropDisabledTestCaseIds.sql` (`ALTER TABLE test_suites DROP COLUMN
disabled_test_case_ids`) → `./gradlew generateJooq` → commit the regenerated
`src/main/java-generated/**/TestSuites.java` and `TestSuitesRecord.java` → update
`JooqTableSchemaResolverTest` (the `test_suites` schema assertion) and
`DatasetMigrationFunctionalTests` (which asserts the column exists) → update `docs/database-schema.md`.
That change also removes the field from the public `test_suites` query entity schema.

## Test plan

1. New functional regression tests above fail on `development` and pass after the change.
2. `./gradlew test --tests "*TestSuiteRunFunctionalTests*" --tests "*SuiteSnapshotFunctionalTests*"
   --tests "*TestSuiteCloneFunctionalTests*" --tests "*DatasetDetachFunctionalTests*"
   --tests "*TestSuiteDatasetFunctionalTests*"` — end-to-end run creation, snapshot, clone, detach.
3. `./gradlew :evaluation-runner-core:test :eval-cli:test` — the response DTO lives in the runner module and
   is consumed by the CLI.
4. `./gradlew clean build` — unit + Testcontainers suite, Checkstyle, Spotless, ArchUnit
   (`LayeredArchitectureTest`, `JooqSchemaDriftTest` — the schema is unchanged, so no drift), plus
   `./gradlew spotlessApply` before commit.
