## 1. Update V1.22 migration

- [x] 1.1 Open `src/main/resources/db/migration/meta/POSTGRES/V1.22__IntroduceDataset.sql` and append a new step "11. Backfill snapshotVersion and datasetRef in stored suite snapshots" after the existing step 10 (revalidation_tasks FK retarget). Match the existing comment-banner style. Wrap the UPDATE statement (and only the UPDATE statement, no surrounding comments) with explicit single-line marker comments `-- BEGIN backfill suite_snapshot v2 shape` immediately before and `-- END backfill suite_snapshot v2 shape` immediately after — the functional test in 3.4 extracts the block between these markers from the classpath resource so the test exercises the exact production SQL.
- [x] 1.2 Write the UPDATE statement:
  - Target: `test_suite_runs r`
  - Join: `FROM test_suites ts WHERE r.test_suite_id = ts.id`
  - Guard: `AND r.suite_snapshot IS NOT NULL AND (r.suite_snapshot -> 'datasetRef') IS NULL` (semantically equivalent to "datasetRef key absent" for our data; preferred over the `?` key-exists operator because JDBC PreparedStatement treats `?` as a parameter placeholder, breaking jOOQ/JDBC execution paths the test helper uses)
  - SET: `suite_snapshot = jsonb_set(jsonb_set(r.suite_snapshot, '{snapshotVersion}', '"2"'::jsonb, true), '{datasetRef}', jsonb_build_object('id', ts.id, 'version', 1, 'name', 'DATASET_' || ts.name), true)`
- [x] 1.3 Add a short header comment explaining the WHY (pre-V1.22 snapshots lack `snapshotVersion` and `datasetRef`; backfilling here means application code reads a uniform v2 shape) and the determinism source (D1 of `introduce-dataset-entity`: `dataset.id = source_suite.id`).
- [x] 1.4 Verify the SQL parses by running `./gradlew generateJooq` (boots Zonky EmbeddedPostgres and applies both migration sets); a syntax error in V1.22 will fail this task. Alternatively rely on the functional-test boot in step 4 to catch syntax errors via the Testcontainers Postgres path.

## 2. Update the suite-run-snapshot main spec (deferred to archive)

Note: the spec delta lives in `openspec/changes/suite-snapshot-dataset-backward-compatibility/specs/suite-run-snapshot/spec.md`. The main spec at `openspec/specs/suite-run-snapshot/spec.md` is synced from the delta during `opsx:archive`. No manual edit to the main spec during implementation.

- [x] 2.1 Confirm the delta spec compiles cleanly (`openspec validate suite-snapshot-dataset-backward-compatibility` exits zero). Adjust delta wording if validation surfaces a mismatch with the main spec's requirement headers.

## 3. Functional test for the backfill outcome

- [x] 3.1 In `src/test/java/com/epam/aidial/evaluation/functional/tests/SuiteSnapshotFunctionalTests.java`, add a new nested `@Nested` class `BackfillForLegacySnapshots` (or extend the existing nesting structure if it already covers migration-time concerns).
- [x] 3.2 Add a helper in `MetaTestDataHelper` (or extend an existing one) named `insertLegacyShapedRun(UUID runId, UUID suiteId, Map<String,Object> legacyJsonNode)` that writes directly into `test_suite_runs` with the pre-V1.22 JSON shape: `snapshotVersion = "1"` present (matching what the original `@Builder.Default = "1"` producer wrote) and `datasetRef` absent. Use the existing `DSLContext metaDsl` field on `MetaTestDataHelper` to insert via `metaDsl.insertInto(TEST_SUITE_RUNS) ... .set(TEST_SUITE_RUNS.SUITE_SNAPSHOT, JSONB.valueOf(legacyJson)) ... .execute()`. Build the legacy-shaped JSON string with the application `ObjectMapper` from a `Map<String, Object>` whose `snapshotVersion` entry is `"1"` and which has no `datasetRef` entry; do NOT inject `JdbcTemplate` (the helper is jOOQ-only).
- [x] 3.3 Test method `shouldBackfillLegacySnapshotsWithDatasetRef`:
  - Inside a `@BeforeEach` or via the test setup, create a test suite via the helper API (which by V1.22 also creates a dataset with `dataset.id = suite.id`).
  - Insert a `test_suite_runs` row whose `suite_snapshot` JSON has `snapshotVersion = "1"` (matching the pre-V1.22 producer), `suiteType`, `deploymentRef`, `requestTemplate`, `responseColumns`, `testCaseSchema`, `inputBindings` — and NO `datasetRef` key. (Note: V1.22 has already run by the time `@SpringBootTest` boots, so this row simulates a pre-V1.22 snapshot that survived the migration. To test the backfill itself, the row must be inserted BEFORE the migration runs; see 3.4 for the approach.)
- [x] 3.4 Use the post-migration approach: after V1.22 has been applied at boot, write a legacy-shaped row via the helper (with `snapshotVersion = "1"`, no `datasetRef`), then execute the V1.22 backfill SQL block against it. **To keep the test in sync with the production migration**, load the SQL block by reading `V1.22__IntroduceDataset.sql` from the classpath (`getClass().getResourceAsStream("/db/migration/meta/POSTGRES/V1.22__IntroduceDataset.sql")`) and extract step 11 between explicit `-- BEGIN backfill suite_snapshot v2 shape` / `-- END backfill suite_snapshot v2 shape` marker comments (add these markers in task 1.1). Execute the extracted SQL via `metaDsl.execute(extractedSql)`. Assert the post-update row is v2-shaped. This validates the UPDATE's effect and idempotency against the **exact** SQL that V1.22 runs; an end-to-end V1.22 application against a pre-V1.22 dump is out of scope for this change.
- [x] 3.5 Assertions for the chosen approach:
  - Read the row back via `TestSuiteRunRepository.findById(runId)`.
  - Deserialize `suiteSnapshot` via the application `ObjectMapper`.
  - Assert `snapshot.getSnapshotVersion()` equals `"2"`.
  - Assert `snapshot.getDatasetRef() != null`.
  - Assert `snapshot.getDatasetRef().getId()` equals `suite.getId()` (by D1's `dataset.id = source_suite.id` invariant).
  - Assert `snapshot.getDatasetRef().getVersion()` equals `1`.
  - Assert `snapshot.getDatasetRef().getName()` equals `"DATASET_" + suite.getName()`.
  - Assert other fields (`deploymentRef`, `requestTemplate`, `testCaseSchema`, ...) are unchanged from the inserted legacy JSON.
- [x] 3.6 Idempotency assertion: re-apply the same UPDATE (via helper) against the now-v2 row; assert `datasetRef` and `snapshotVersion` are unchanged.
- [x] 3.7 NULL-snapshot assertion: insert a `test_suite_runs` row with `suite_snapshot IS NULL`; after the backfill UPDATE runs, assert the row's `suite_snapshot` is still NULL (the guard skips it).
- [x] 3.8 Unit test in `service.domain.dto.SuiteSnapshotDtoTest` (create if absent): deserialize JSON `{"suiteType":"DEPLOYMENT"}` (no `snapshotVersion` key); assert `dto.getSnapshotVersion()` equals `"2"`. This verifies the Jackson `@Builder.Default` fallback for the absent-key defense-in-depth path.
- [x] 3.9 In the same `BackfillForLegacySnapshots` functional test, perform `GET /test-suite-runs/{id}` against the backfilled run and assert response body's `suiteSnapshot.snapshotVersion` equals `"2"`, `suiteSnapshot.datasetRef.id` equals `suiteId.toString()`, `suiteSnapshot.datasetRef.version` equals `1`, and `suiteSnapshot.datasetRef.name` equals `"DATASET_" + suite.name`.

## 4. Verification

- [x] 4.1 Run the new functional test: `./gradlew test --tests "com.epam.aidial.evaluation.functional.tests.SuiteSnapshotFunctionalTests"`. Confirm green.
- [x] 4.2 Run the existing functional test suites that exercise `resolveSnapshot`: `./gradlew test --tests "com.epam.aidial.evaluation.functional.tests.LargeDatasetSnapshotFunctionalTests" --tests "com.epam.aidial.evaluation.service.domain.SuiteSnapshotBuilderTest" --tests "com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJobTest"`. Confirm green; the post-backfill data shape does not affect these tests but it is worth confirming no regression.
- [x] 4.3 Run `./gradlew checkstyleMain checkstyleTest`. Confirm clean.
- [x] 4.4 Run `./gradlew test`. Full suite must pass before this change can be archived. In particular, `JooqSchemaDriftTest` must remain green (no schema change → no jOOQ regeneration needed).
- [x] 4.5 Run `openspec validate suite-snapshot-dataset-backward-compatibility --strict`. Confirm zero errors.

## 5. Pre-archive checks

- [x] 5.1 Re-read `openspec/config.yaml` `rules.archive` section and address any items not auto-injected by the openspec tooling (delta spec sync, AGENTS.md review, etc.).
- [x] 5.2 Check whether AGENTS.md's "Snapshot version handling" paragraph needs updating. The current text says "Genuine v1 snapshots set the field explicitly to `'1'` and are rejected" — after this change, no v1 snapshots exist at all (the backfill normalizes them). Reword to: "After the V1.22 backfill, every stored snapshot carries `snapshotVersion = '2'` and a populated `datasetRef`; the `UnsupportedSnapshotVersionException` path remains as defense-in-depth for future producer bugs."
- [x] 5.3 Confirm `docs/database-schema.md` does not need updating (no schema diff — the change is data-only inside an existing JSONB column).
- [x] 5.4 Confirm `openspec/specs/README.md` does not need updating (no spec folder added or removed; `suite-run-snapshot` summary remains accurate at the one-line level — the delta is internal to the spec).
