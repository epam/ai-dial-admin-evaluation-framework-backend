## 1. Filter Whitelist

- [x] 1.1 Add `id` (UUID, `eq`/`in`), `startedAt` (LONG, `gt`/`gte`/`lt`/`lte`), and `completedAt` (LONG, `gt`/`gte`/`lt`/`lte`) entries to `FilterWhitelists.TEST_SUITE_RUNS` in `FilterWhitelists.java`

## 2. Tests

- [x] 2.1 Add functional test `shouldFilterRunsById` to `TestSuiteRunFunctionalTests` — creates two runs, filters by `id:eq:<uuid>` of one, asserts only that run is returned
- [x] 2.2 Add functional test `shouldFilterRunsByIdIn` to `TestSuiteRunFunctionalTests` — creates three runs, filters by `id:in:<uuid1>,<uuid2>`, asserts exactly two runs returned
- [x] 2.3 Add functional test `shouldFilterRunsByStartedAt` to `TestSuiteRunFunctionalTests` — creates and awaits terminal run, filters by `startedAt:gte:<value>`, asserts run is included; pending runs (null `startedAt`) are excluded
- [x] 2.4 Add functional test `shouldFilterRunsByCompletedAt` to `TestSuiteRunFunctionalTests` — creates and awaits terminal run, filters by `completedAt:lte:<value>`, asserts completed run is included; pending/running runs (null `completedAt`) are excluded
- [ ] 2.5 Run new tests: `./gradlew test --tests "com.epam.aidial.evaluation.functional.tests.TestSuiteRunFunctionalTests"`

## 3. Spec Sync

- [x] 3.1 Update `openspec/specs/test-suite-runs/spec.md` — sync the MODIFIED "List test suite runs with filtering" requirement from the delta spec (add `id`, `startedAt`, `completedAt` scenarios)
