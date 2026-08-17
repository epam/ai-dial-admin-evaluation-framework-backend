## 1. Failing regression coverage for GH #151

- [ ] 1.1 Replace `appendDisabledTestCaseIds` in
      `src/test/java/com/epam/aidial/evaluation/functional/helper/MetaTestDataHelper.java` with an
      intent-named back door `forceLegacyDisabledTestCaseIds(UUID suiteId, List<UUID> testCaseIds)` (same raw
      `UPDATE test_suites SET disabled_test_case_ids = COALESCE(...) || ?::jsonb`, kept inside the helper),
      and delete the `!enabled` branch + flag from `seedManyTestCases` (done: helper compiles, no test-only
      writer of the column remains outside these two places).
- [ ] 1.2 Add `src/test/java/com/epam/aidial/evaluation/functional/tests/LegacyDisabledTestCaseIdsFunctionalTests.java`
      (`@PostgresFunctionalTests`) with the #151 reproduction: a dataset of N valid test cases, a suite whose
      column is seeded via 1.1 with a subset, no `testCaseFilter` → `POST /test-suites/{id}/runs` persists
      `numberOfTestCases = N` and the snapshot materializes N `test_case_run_inputs` rows (assert via
      repositories, not raw SQL) (done: test written and observed FAILING on the current code).
- [ ] 1.3 Add a second scenario to the same class: the suite has a `testCaseFilter` matching only test cases
      that are also named in the seeded legacy column → run creation succeeds (HTTP 202) and the snapshot
      contains exactly the filter-matching cases, instead of 409 "Suite has no valid and enabled test cases"
      (done: test written and observed FAILING with the 409 from #151).
- [ ] 1.4 Run `./gradlew test --tests "*LegacyDisabledTestCaseIdsFunctionalTests"` and record both failures
      (done: failure output confirms the pre-change behavior described in proposal.md — Why).

## 2. Selection path — stop reading the column

- [ ] 2.1 Drop `excludedIds` from `service/domain/job/RunnableTestCaseSelector` (`countRunnable`,
      `loadRunnablePage`) and update its javadoc to describe the runnable set as validity + `testCaseFilter`
      (done: interface has no exclusion parameter).
- [ ] 2.2 Update `experimental/query/service/QueryDslRunnableTestCaseSelector` to the new signatures,
      delegating to the renamed repository methods from 3.1 (done: compiles against the new interface).
- [ ] 2.3 Delete `service/domain/RunnableTestCaseCounter.java` and its unit test
      `src/test/java/com/epam/aidial/evaluation/service/domain/RunnableTestCaseCounterTest.java`; inject
      `RunnableTestCaseSelector` into `service/domain/TestSuiteRunService` and call `countRunnable(datasetId,
      testCaseFilter)` in `createRun` (done: no `RunnableTestCaseCounter` reference remains).
- [ ] 2.4 Remove the `deserializeDisabledIds` helper and its call site from
      `service/domain/TestSuiteRunService.java` (done: the guard reads only `datasetId` + `testCaseFilter`).
- [ ] 2.5 Remove the `deserializeDisabledIds` helper and its call site from
      `service/domain/job/TestSuiteEvaluationJob.java` snapshot loop, passing only `datasetId`,
      `testCaseFilter`, `offset`, `limit` to `loadRunnablePage` (done: snapshot paging carries no exclusion
      argument).
- [ ] 2.6 Update `service/domain/job/InProcessEvaluationExecutor.fetchPage` legacy fallback to call
      `findValidByDatasetId(datasetId, offset, PAGE_SIZE)` and drop the comment explaining the empty
      exclusion list (done: no `List.of()` placeholder remains).

## 3. Repository, model, and record mapper

- [ ] 3.1 In `data/db/repository/TestCaseRepository.java` + `PostgresTestCaseRepository.java`, rename
      `findValidByDatasetIdExcludingIds{,Matching}` → `findValidByDatasetId{,Matching}` and
      `countValidByDatasetIdExcludingIds{,Matching}` → `countValidByDatasetId{,Matching}`, dropping the
      `Collection<UUID>` parameter; replace `validNotExcludedCondition` with `validCondition(UUID datasetId)`
      and delete the `NOT (test_cases.id = ANY({0}::text[]))` predicate plus its plan-cache javadoc (done:
      no exclusion SQL remains in the repository).
- [ ] 3.2 Remove the `disabledTestCaseIds` field from `data/db/model/TestSuite.java` and stop mapping it in
      `data/db/mapper/TestSuiteRecordMapper.java` (done: the generated `TestSuitesRecord` getter is no longer
      called anywhere).
- [ ] 3.3 In `data/db/repository/PostgresTestSuiteRepository.java`, delete the three
      `.set(TEST_SUITES.DISABLED_TEST_CASE_IDS, …)` calls in `create`, `update`, and `createWithId` so
      inserts fall back to the column `DEFAULT '[]'::jsonb` (done: column not referenced by those builders).
- [ ] 3.4 Change `TestSuiteRepository.updateDatasetId` / its Postgres impl to
      `updateDatasetId(UUID suiteId, UUID newDatasetId, long updatedAt)`, removing the exclusion parameter
      and its `.set(...)` (done: rebind touches `dataset_id`, `version`, `updated_at_ms` only).
- [ ] 3.5 Run `./gradlew test --tests "*JooqSchemaDriftTest" --tests "*JdbcTemplateFenceTest"
      --tests "*LayeredArchitectureTest"` (done: green — schema untouched, no layering or fence regressions).

## 4. API surface, mapper, and clone/detach

- [ ] 4.1 Remove `disabledTestCaseIds` (field, `@Size`, `@Schema`) from
      `service/domain/dto/TestSuiteRequestDto.java` and from
      `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/dto/TestSuiteResponseDto.java`;
      delete `MAX_DISABLED_TC_IDS` from `constants/ValidationConstants.java` (done: no compile reference to
      the constant or the DTO fields).
- [ ] 4.2 In `service/domain/mapper/TestSuiteMapper.java`, delete `serializeDisabledIds`,
      `deserializeDisabledIds`, `remapDisabledIds`, the five field-mapping sites (entity→response,
      request→entity create/update, clone-to-request, clone entity), and `STRING_LIST_TYPE` if it becomes
      unused (done: the mapper has no reference to the concept).
- [ ] 4.3 Remove the remap block from `service/domain/TestSuiteCloneService.java` (step "5pre") and from
      `service/domain/TestSuiteService.java`'s detach/private-clone path, adapting both to the new
      `updateDatasetId` signature (done: neither service mentions exclusions).
- [ ] 4.4 Change `service/domain/DatasetCloneService.cloneRowAndTestCases` to return `void`, deleting the
      `Map<UUID, UUID> idMap` accumulation and updating its javadoc (done: no per-test-case map is built;
      all three call sites compile).
- [ ] 4.5 Update the descriptions that reference the field: the clone-endpoint text in
      `web/controller/TestSuiteController.java` and the `testCaseFilter` `@Schema` descriptions in
      `TestSuiteRequestDto` / `TestSuiteResponseDto` that say the filter is "combined with
      `disabledTestCaseIds`" (done: descriptions describe validity + filter only).
- [ ] 4.6 Remove `disabledTestCaseIds` from the ~17 OpenAPI example files under
      `src/main/resources/openapi/examples/` (test-suite POST/PUT/GET/clone requests and responses) (done:
      `grep -rl disabledTestCaseIds src/main/resources/openapi` returns nothing).

## 5. Test suite cleanup and verification

- [ ] 5.1 Delete the tests whose subject is removed: `LargeDatasetSnapshotFunctionalTests` (10000-element
      `text[] = ANY` planner smoke), `SuiteSnapshotFunctionalTests` "snapshot excludes test cases listed in
      the suite's disabledTestCaseIds", `DatasetDetachFunctionalTests.shouldRemapDisabledTestCaseIdsToClonedIds`,
      `TestSuiteDatasetFunctionalTests.disabledTestCaseIdsRoundTrip` and its empty-list assertion, and the
      remap assertions inside `TestSuiteCloneFunctionalTests` (keep the surrounding cross-pagination-boundary
      clone test) (done: no test asserts exclusion behavior).
- [ ] 5.2 Drop the vestigial `enabled` flag from `BaseTestCaseBulkPatchFunctionalTests.createTestCase` and
      `seedManyTestCases` call sites (no caller passes `false`), and remove the `TestSuiteRunFunctionalTests`
      `updateSuiteDisabledTestCaseIds` helper (done: bulk-patch and run tests compile with no exclusion
      fixture).
- [ ] 5.3 Update the unit tests that build suites or stub the clone service:
      `TestSuiteMapperCloneTest`, `TestSuiteServiceTest`, `TestSuiteCloneServiceTest`, `SuiteSnapshotBuilderTest`,
      `TryItOutServiceTest`, `TestSuiteEvaluationJobTest`, `InProcessEvaluationExecutorTest` — drop
      `.disabledTestCaseIds(...)` builder calls and adjust verifications to the new signatures (done: all
      compile and pass).
- [ ] 5.4 Rework `DatasetCloneServiceTest` cases that asserted the returned id map to assert the clone via
      captured repository inserts (new ids, repointed `datasetId`, `@ef/datasets/` ref rewrite) (done: the
      `void` signature is fully covered).
- [ ] 5.5 Re-run `./gradlew test --tests "*LegacyDisabledTestCaseIdsFunctionalTests"` (done: both scenarios
      from group 1 now PASS), then
      `./gradlew test --tests "*TestSuiteRunFunctionalTests*" --tests "*SuiteSnapshotFunctionalTests*"
      --tests "*TestSuiteCloneFunctionalTests*" --tests "*DatasetDetachFunctionalTests*"
      --tests "*TestSuiteDatasetFunctionalTests*"` (done: green).
- [ ] 5.6 Run `./gradlew :evaluation-runner-core:test :eval-cli:test`, then `./gradlew spotlessApply` and
      `./gradlew clean build` (done: runner/CLI modules green after the response-DTO change; full build,
      Checkstyle, Spotless, and ArchUnit green).

## 6. Documentation and spec sync

- [ ] 6.1 Update `docs/patterns/dataset-entity.md` — remove `disabledTestCaseIds` as a headline concept and
      point exclusion at `docs/patterns/test-cases-query-entity.md` / the `suite-test-case-filter` spec
      (done: the doc describes `DatasetSchemaProvider`, `dataset.id` vs `suite.id`, and visibility rules
      only).
- [ ] 6.2 Update `AGENTS.md` — the Unique Patterns row for `Dataset Entity` lists `disabledTestCaseIds` in
      its "why it matters" cell; replace it with the filter-based mechanism (done: no `disabledTestCaseIds`
      reference remains in AGENTS.md).
- [ ] 6.3 Update `docs/database-schema.md` — mark `test_suites.disabled_test_case_ids` as retained but
      unread/unwritten by application code, with removal pending a follow-up change (done: the column's row
      states the divergence and no doc text implies it affects runs).
- [ ] 6.4 Update the non-requirement prose that the delta specs cannot carry: the `test-suites` spec Key
      Terms bullet and the `test-cases` spec Purpose paragraph, both of which describe the field (done: both
      point at `testCaseFilter` instead). Apply during `/opsx:sync`, alongside the delta merge.
- [ ] 6.5 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy — the `test-suites`,
      `suite-test-case-filter`, `test-suite-clone`, and `detach-dataset` summaries all describe
      `disabledTestCaseIds` (done: index reflects filter-only selection).
- [ ] 6.6 Confirm no `openspec/config.yaml` update is needed (no new layer, convention, tooling, or glossary
      term — the change removes a feature within the existing architecture) and record that decision in the
      archive check (done: config.yaml left unchanged, deliberately).
