## 1. Meta schema and Flyway wiring

- [ ] 1.1 Add `src/main/resources/db/migration/meta/POSTGRES/V1.32__CreateRunMetricSnapshotsTable.sql` mirroring the analytics `V1.6` column set, plus `uq_run_metric_snapshots_computation_tsmd` on `(computation_id, tsmd_id)`, `idx_run_metric_snapshots_run` on `(test_suite_run_id)`, and `FOREIGN KEY (test_suite_run_id) REFERENCES test_suite_runs(id) ON DELETE CASCADE`. Include a header comment naming `V1_33` as the companion Java migration (design D2). Verify by applying meta migrations against a clean database and confirming the table, both indexes, and the FK exist.
- [ ] 1.2 Add `V1_33__CopyRunMetricSnapshotsFromAnalytics` as a Flyway `BaseJavaMigration` in the meta migration Java package, taking the analytics `DataSource` and analytics vendor string via constructor. No DDL (design D1). Verify it compiles and `getChecksum()` returns a stable constant.
- [ ] 1.3 Implement the migration body: skip with a log line when the analytics vendor is not `POSTGRES` or when `information_schema.tables` shows no analytics `run_metric_snapshots` (design D5); otherwise read on a separate read-only analytics connection with `autoCommit=false` + `setFetchSize`, and write through `context.getConnection()` (design D4). Verify via the tests in group 7.
- [ ] 1.4 Implement the orphan guard and batching: `INSERT ... SELECT ... WHERE EXISTS (SELECT 1 FROM test_suite_runs WHERE id = ?) ON CONFLICT (computation_id, tsmd_id) DO NOTHING`, batched every 1000 rows, summing update counts and logging copied vs dropped-orphan counts (design D6). Verify via the tests in group 7.
- [ ] 1.5 Wire the migration into `MetaFlywayConfiguration`: inject `@Qualifier("analyticsDataSource") DataSource` and the analytics vendor, register the instance via `.javaMigrations(...)`, and take the analytics `Flyway` bean as a parameter so meta always migrates after analytics (design D2, D3). Verify one functional test boots the application context successfully.

## 2. Data layer move

- [ ] 2.1 Move `RunMetricSnapshot` from `data.db.analytics.model` to `data.db.model`. Verify compilation.
- [ ] 2.2 Move `RunMetricSnapshotRecordMapper` from `data.db.analytics.mapper` to `data.db.mapper`. Verify compilation.
- [ ] 2.3 Move `RunMetricSnapshotRepository` and `PostgresRunMetricSnapshotRepository` from `data.db.analytics.repository` to `data.db.repository`; switch `@Qualifier("analyticsDsl")` → `"metaDsl"`, `@ConditionalOnProperty(name = "datasource.analytics.vendor")` → `"datasource.meta.vendor"`, and the static import from `jooq.analytics.Tables` → `jooq.meta.Tables` (design D10). Verify compilation and that `LayeredArchitectureTest` passes.

## 3. Service layer move and transaction boundaries

- [ ] 3.1 Move `RunMetricSnapshotService` from `service.domain.analytics` to `service.domain` and switch all four methods from `@Transactional("analyticsTransactionManager")` to `"metaTransactionManager"`. Verify `batchCreate`'s run-existence check and insert now share one transaction (design D10).
- [ ] 3.2 Move `RunMetricSnapshotBatchWriteItemDto`, `RunMetricSnapshotBatchWriteRequestDto`, and `RunMetricSnapshotResponseDto` from `service.domain.dto.analytics` to `service.domain.dto`, and `RunMetricSnapshotMapper` accordingly if its package references change. Verify `RunMetricSnapshotMapperTest` passes.
- [ ] 3.3 Hoist `EvalSummaryExportService`'s `runMetricSnapshotRepository.findByRunId` call out of the `analyticsTransactionTemplate` block into the existing `metaTransactionTemplate` block, carrying the result on `MetaSetup` (design D9). Verify `EvalSummaryExportFunctionalTests` passes.
- [ ] 3.4 Hoist `RunComparisonService`'s `findByRunIdAndComputationId` call ahead of its `analyticsTransactionTemplate.execute` block so the meta read no longer sits inside an analytics transaction (design D9). Verify `RunComparisonServiceTest` and `RunComparisonFunctionalTests` pass.
- [ ] 3.5 Update imports in `EvalSummariesSchemaProvider`, `MetricScoreComputationExecutor`, `MetricFieldDiscoverer`, `EvalSummaryExportColumnPlanner`, and `InProcessMetricEvaluationExecutor`. Verify compilation and that `./gradlew checkstyleMain` passes.

## 4. Controllers and OpenAPI

- [ ] 4.1 Change `RunMetricSnapshotController`'s `@RequestMapping` to `/api/v1/run-metric-snapshots` and move the class out of any analytics-specific package/import grouping. Verify `RunMetricSnapshotFunctionalTests` passes against the new path.
- [ ] 4.2 Add a `@Deprecated(forRemoval = true)` alias controller at `/api/v1/analytics/run-metric-snapshots` delegating both `GET` and `POST` to the same `RunMetricSnapshotService`, with `@Operation(deprecated = true)` and descriptions naming the replacement path, and distinct operationIds (design D8). Verify a functional test asserts the alias returns the same payload and status as the canonical path for both verbs.
- [ ] 4.3 Rename `src/main/resources/openapi/examples/run-metric-snapshot-*.json` to the `OpenApiExampleCustomizer` convention for the new path (`api-v1-run-metric-snapshots-POST-request-minimal.json`, `api-v1-run-metric-snapshots-GET-response-200-minimal.json`, and the batch-write response). Verify a test asserts these examples appear in `/v3/api-docs` — they are currently never injected because the old filenames do not match the convention.
- [ ] 4.4 Verify the generated OpenAPI marks only the alias operations `deprecated: true` and leaves the canonical operations undeprecated, via assertions on `/v3/api-docs`.

## 5. jOOQ codegen and drift guard

- [ ] 5.1 Change the analytics generator in `build.gradle` to `.withExcludes("flyway_schema_history|run_metric_snapshots")` (design D7). Verify the analytics exclude does not affect the meta generator.
- [ ] 5.2 Run `./gradlew generateJooq`, delete the stale generated `jooq/analytics/tables/RunMetricSnapshots.java` and its record, and commit the new `jooq/meta` sources. Verify `RUN_METRIC_SNAPSHOTS` now resolves only under `jooq.meta.Tables`.
- [ ] 5.3 Update `JooqSchemaDriftTest`: remove `Tables.RUN_METRIC_SNAPSHOTS` from the analytics list and add the meta table to the meta list as an FQN, matching how that test already writes meta tables. Verify `./gradlew test --tests "*JooqSchemaDriftTest"` passes.

## 6. Test helpers and functional test churn

- [ ] 6.1 Move `cleanupRunMetricSnapshots`, `countRunMetricSnapshots`, `createRunMetricSnapshot`, and `findRunMetricSnapshotsByRunId` from `AnalyticsTestDataHelper` to `MetaTestDataHelper`. Verify no raw snapshot SQL remains outside helpers.
- [ ] 6.2 Move the `DELETE FROM run_metric_snapshots` truncate in `PostgresTestPersistenceService` from `cleanupAnalyticsTables` to the meta cleanup path. Verify functional tests still start from a clean state across classes.
- [ ] 6.3 Update the ~14 functional test classes that reference `RunMetricSnapshot` or the helper methods to use `MetaTestDataHelper` and the new imports. Verify `./gradlew test` compiles the test source set.
- [ ] 6.4 Repoint the 7 functional REST call sites from `/analytics/run-metric-snapshots` to `/run-metric-snapshots`, keeping the alias coverage added in 4.2. Verify the affected suites pass.
- [ ] 6.5 Add a functional test asserting that deleting a terminal run removes its `run_metric_snapshots` rows via cascade, and that its analytics result rows remain (per the `test-suite-runs` delta). Verify both assertions with repository/helper reads, not raw SQL.

## 7. Migration tests

- [ ] 7.1 Add a test that drives `V1_33__CopyRunMetricSnapshotsFromAnalytics` directly against two Testcontainers datasources — the copy cannot be exercised through an application boot because Flyway runs before fixtures exist (design, Migration Plan). Verify rows present in the analytics source land in the meta target with identical column values.
- [ ] 7.2 Add a case re-running the migration against an already-populated target and verify it inserts nothing and does not fail.
- [ ] 7.3 Add a case where a source row's `test_suite_run_id` is absent from meta and verify the row is skipped, the dropped count is logged, and the migration completes successfully.
- [ ] 7.4 Add a case where the analytics source table does not exist and verify the migration skips and completes successfully.

## 8. Documentation, specs, and config

- [ ] 8.1 Update `docs/database-schema.md`: add the meta `run_metric_snapshots` table with its FK and indexes, and mark the analytics table frozen and unread as of this release. Verify both statements are present.
- [ ] 8.2 Update `docs/key-packages.md` for the moved packages. Verify no stale `data.db.analytics.*RunMetricSnapshot*` or `service.domain.analytics.RunMetricSnapshotService` references remain.
- [ ] 8.3 Update `docs/patterns/computation-versioning.md`, `docs/patterns/eval-summaries-read-surface.md`, and `docs/patterns/dual-datasource.md` to reflect that snapshots are meta-resident. Verify by grepping those files for "analytics" near snapshot references.
- [ ] 8.4 Update `openspec/config.yaml` per Config Maintenance Policy (done: relevant sections updated) — Flyway Java migrations are now a supported migration type, restricted to data movement, registered explicitly, with meta ordered after analytics.
- [ ] 8.5 Update AGENTS.md per AGENTS.md Maintenance guidelines (done: relevant sections reflect the change) — the moved packages/qualifiers and the new Java-migration convention.
- [ ] 8.6 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: index reflects current specs) — the `metrics-storage` summary currently describes run metric snapshots as part of the analytics table and is now inaccurate.

## 9. Final verification

- [ ] 9.1 Run `./gradlew spotlessApply` then `./gradlew clean build` and verify the full build passes, including Checkstyle, `LayeredArchitectureTest`, `LoggingConventionTest`, `JdbcTemplateFenceTest`, and `JooqSchemaDriftTest`.
- [ ] 9.2 Verify no production code path reads or writes the analytics `run_metric_snapshots` table, by grepping for the table name and confirming remaining hits are only the historical analytics migrations `V1.6`, `V1.8`, and `V1.12`.
- [ ] 9.3 Run `/review` and address findings before opening the PR.
