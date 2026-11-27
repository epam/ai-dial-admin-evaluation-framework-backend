## 1. Configuration and scheduler API discovery

- [x] 1.1 Read `scheduler-openapi.json` and record in design.md whether `DELETE /schedules/{id}` and cron timezone exist and the exact response field names (done: findings recorded in design.md Context and decisions; delete and timezone exist, list items omit properties)
- [x] 1.2 Add `SchedulerProperties` (`@ConfigurationProperties("dial.scheduler")`, `@Validated`, no field-initializer defaults; `@NotBlank callbackBaseUrl` when enabled) in the scanned properties package (done: binds and fails fast on blank callback URL)
- [x] 1.3 Add `dial.scheduler.*` defaults to `application.yml` (`enabled=false`, `app-id=${SCHEDULER_APP_ID:public/dial_scheduler__1.0.0}`, `callback-base-url=${SCHEDULER_CALLBACK_BASE_URL:}`, `service-id=${SCHEDULER_SERVICE_ID:dial-native}`, `timeout-seconds=120`) and rows in `docs/configuration.md` with all six columns (done: configuration-docs spec satisfied)
- [x] 1.4 Unit test `SchedulerProperties` validation (enabled + blank callback URL fails; disabled passes)

## 2. Mapping table and repository

- [x] 2.1 Add Flyway `V1.39__CreateTestSuiteSchedulesTable.sql` (`schedule_id VARCHAR(255) PK`, `test_suite_id VARCHAR(36) NOT NULL` FK to `test_suites(id)` ON DELETE CASCADE, `created_by VARCHAR(255) NOT NULL`, `created_at BIGINT NOT NULL`, index on `test_suite_id`), following V1.32 conventions
- [x] 2.2 Run `./gradlew generateJooq` and commit the generated diff (done: `JooqSchemaDriftTest` passes); update `docs/database-schema.md` (table section, Tables Overview, "Last sync" line)
- [x] 2.3 Add `TestSuiteSchedule` model, `TestSuiteScheduleRecordMapper` and `TestSuiteScheduleRepository` + `PostgresTestSuiteScheduleRepository` (`@Qualifier("metaDsl")`, `@LogExecution`): `save` (explicit `createdAt`), `findById`, `findPageByTestSuiteId` (`Page<T>`/`PageRequest`), `deleteById`
- [x] 2.4 Add `cleanupTestSuiteSchedules`/`createTestSuiteSchedule` helpers to `MetaTestDataHelper` and a repository functional test (nested in `PostgresFunctionalTests`): save/find/page by suite, FK cascade on suite delete; run it (done: passes)

## 3. Scheduler client

- [x] 3.1 Add scheduler DTOs in `client/scheduler/dto` per `scheduler-openapi.json` (schedule request with `timezone`/`description`/`trigger.cron.fields`/`properties`, schedule response, run list item, page wrapper; snake_case via `@JsonProperty`, unknown fields ignored)
- [x] 3.2 Add `SchedulerClientConfiguration` with `dialSchedulerRestClient` reusing `DialCoreClientConfiguration.callerCredentialInterceptor()` and `tracingInterceptor(...)`, `@ConditionalOnProperty(dial.scheduler.enabled)`
- [x] 3.3 Implement `DialSchedulerClient` (`@Service @LogExecution`): create, get, update, pause, resume, delete, listRuns; URIs via `UriComponentsBuilder` with `appId` kept as a path; no retry; map `RestClientResponseException` to `DialCoreClientException` with exception as last log arg
- [x] 3.4 Unit test `DialSchedulerClientTest` with `MockRestServiceServer`: URLs (incl. `/` in app id and trailing slash), bodies, caller credential header, 404/503 mapping, exactly one request on 503
- [x] 3.5 Run `./gradlew :test --tests "*DialSchedulerClientTest"`, `checkstyleMain`, `checkstyleTest`, `spotlessApply` (done: all pass)

## 4. Service layer

- [x] 4.1 Extract run-config validation from `TestSuiteRunService.createRun` into an injectable `RunConfigValidator` (only if not already reusable) and call it from both services; adjust existing unit tests
- [x] 4.2 Add `ScheduleCronMapper` and `ScheduleRequestBuilder` (`service.domain`, injectable) building scheduler bodies with `ObjectNode`/ObjectMapper (no string concatenation); validate timezone with `ZoneId.of`
- [x] 4.3 Add service DTOs (`TestSuiteScheduleRequestDto`, `TestSuiteScheduleResponseDto`, `ScheduleCronDto`, `ScheduleRunDto`) with `@Schema` examples and a MapStruct `TestSuiteScheduleMapper` (epoch-ms timestamps, `paused` from `next_run_time`)
- [x] 4.4 Implement `TestSuiteScheduleService` (`@LogExecution`, `@ConditionalOnProperty`, no DB transaction around HTTP calls) with the flows in design Decision 6, inject `Clock`, use `TestSuiteService` for suite existence and `AuthorResolver` for `created_by`
- [x] 4.5 Unit tests: builder payload and URL, cron/timezone validation, 404 unknown suite and unmapped id (scheduler not called), 400 invalid runConfig, list skips scheduler 404, delete tolerates scheduler 404, create compensates when insert fails; run `./gradlew :test --tests "*TestSuiteScheduleServiceTest" --tests "*ScheduleRequestBuilderTest"`

## 5. Controller and OpenAPI

- [x] 5.1 Implement `TestSuiteScheduleController` (`@RestController @LogExecution @Validated`, `@ConditionalOnProperty`, OpenAPI `@Operation`/`@ApiResponse`, status codes 201/200/204/400/404, `PaginationParamResolver` for the list)
- [x] 5.2 Add an `OpenApiQueryParamCustomizer` registry entry for the schedule list (`page`/`size` only) and OpenAPI example JSON files under `src/main/resources/openapi/examples/` named by registered path (minimal + full for create), plus a test asserting examples appear in `/v3/api-docs`
- [x] 5.3 Controller slice tests for validation errors and disabled-feature 404

## 6. Functional tests and wiring

- [x] 6.1 Add abstract `TestSuiteScheduleFunctionalTests extends BaseFunctionalTest` with a stubbed scheduler (follow the existing DIAL Core stub approach) and register `@Nested class TestSuiteScheduleTests` in `PostgresFunctionalTests`; use `MetaTestDataHelper` for fixtures and the repository for state assertions
- [x] 6.2 Cover create→list→get→edit→pause→resume→delete, unmapped id 404, suite-delete cascade and caller-credential forwarding; run `./gradlew :test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteScheduleTests"` (done: context boots with the flag on and all pass)
- [x] 6.3 Confirm the app context still boots with the flag off (existing functional suite)

## 7. Documentation and specs

- [x] 7.1 Add `docs/patterns/dial-scheduler.md` (flow, mapping table, callback auth prerequisite for `dial-native`, orphan-on-suite-delete caveat, derived `paused`) and register it in `docs/patterns/README.md`
- [x] 7.2 Update `docs/key-packages.md` with `client.scheduler` and the new data/service/web classes
- [x] 7.3 Update AGENTS.md per AGENTS.md Maintenance guidelines (done: add the pattern row to the Unique Patterns table)
- [x] 7.4 Update the relevant spec in `openspec/specs/` (sync `suite-run-scheduling`) and update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: index reflects current specs)
- [x] 7.5 Run `./gradlew spotlessApply checkstyleMain checkstyleTest build` (done: build green)

## 8. Data model for schedule tagging

- [x] 8.1 Add Flyway `V1.40__AddScheduleTagging.sql`: nullable `test_suite_runs.schedule_id VARCHAR(255)` (no FK) with partial index `(schedule_id, created_at_ms DESC) WHERE schedule_id IS NOT NULL`, and nullable `test_suite_schedules.schedule_ref VARCHAR(36)` with a unique partial index
- [x] 8.2 Run `./gradlew generateJooq` and commit the generated diff; update `docs/database-schema.md` for both tables, the V1.40 history row and the Last sync line (done: `JooqSchemaDriftTest` passes)
- [x] 8.3 Add `scheduleId` to the `TestSuiteRun` model, `TestSuiteRunRecordMapper` (`map` and `mapWithoutSnapshot`) and `PostgresTestSuiteRunRepository` (`SELECT_LIST_FIELDS` and `save`)
- [x] 8.4 Add `scheduleRef` to `TestSuiteSchedule`, its RecordMapper and `PostgresTestSuiteScheduleRepository` (`save`, new `findByRef` and `updateRef`); extend the `MetaTestDataHelper` schedule helpers
- [x] 8.5 Repository functional tests: a run's `scheduleId` round-trips and survives deleting the schedule row; `findByRef` and `updateRef`; the ref is unique (done: nested tests pass)

## 9. Run API: scheduleId on responses and in the Query DSL

- [x] 9.1 Add `scheduleId` to `TestSuiteRunResponseDto` (`evaluation-runner-core`) and `TestSuiteRunMapper.toDto` with a `@Schema` example; extend `TestSuiteRunMapperTest`
- [x] 9.2 Confirm the Query DSL `test_suite_runs` entity publishes `schedule_id` (executor and schema provider, no wiring needed) and add functional tests in `TestSuiteRunStructuredQueryFunctionalTests`: `eq` and `in` on `schedule_id` return only tagged runs and manual runs have a null value. The simple REST list gets no `scheduleId` filter (its string `eq`/`ne` are case-insensitive and not null-safe)
- [x] 9.3 Query DSL parity: update `TestSuiteRunsSchemaProviderTest`, `PostgresTestSuiteRunEntityResolverTest` and `QuerySchemaDiscoveryFunctionalTests` expectations, and the hard-coded plain-column counts in `docs/patterns/test-suite-runs-query-entity.md`
- [x] 9.4 Add `scheduleId` to the run response example JSON files and run `OpenApiExamplesTests`
- [x] 9.5 Run `:evaluation-runner-core:test` and `:eval-cli:test` (shared DTO), `TestSuiteRunTests` and the Query DSL run tests (done: all pass)

## 10. Write path: tag resolution and scheduled naming

- [x] 10.1 Add `ScheduleConstants.SCHEDULE_REF_PARAM` and a small `@Service` in the schedule domain (own repository only) that resolves `(scheduleRef, suiteId)` to a schedule id or empty; unknown, blank and other-suite refs are ignored with a warning
- [x] 10.2 `TestSuiteRunService.createRun(suiteId, runConfig, scheduleRef)`: resolve the tag, apply scheduled naming (`<testRunName> #<seq>`, base truncated to fit 255; blank stays `Run #N`) and save `scheduleId`; keep the two-argument overload; update `TestSuiteRunServiceTest`
- [x] 10.3 `TestSuiteRunController.createRun`: optional `scheduleRef` request parameter documented as set by the scheduler; controller tests
- [x] 10.4 `ScheduleRequestBuilder` and `TestSuiteScheduleService`: generate the ref on create and put it in the callback URL query, store it with the link; edit reuses it (a row without one gets one on first edit); unit tests
- [x] 10.5 Run the affected unit tests, `checkstyleMain`, `checkstyleTest`, `LayeredArchitectureTest` and `LoggingConventionTest` (done: all pass)

## 11. Functional tests for tagging

- [x] 11.1 End to end in `TestSuiteScheduleTests`: create a schedule, take the callback URL the fake scheduler recorded, POST it to EF as the scheduler would (runnable-suite fixture from `TestSuiteRunFunctionalTests`), and assert the run is tagged and returned by a Query DSL `schedule_id` filter
- [x] 11.2 A second fire of a schedule with a fixed `testRunName` succeeds with a different suffixed name, while a manual run with a duplicate name still answers 409
- [x] 11.3 An unknown ref and another suite's ref create an untagged run without failing; a request without the parameter is untagged
- [x] 11.4 Deleting the schedule keeps the runs and their `scheduleId`; editing a schedule keeps its ref in the callback URL
- [x] 11.5 Run `TestSuiteScheduleTests`, `TestSuiteRunTests` and the repository tests (done: all pass)

## 12. Fire history with the run id and status

- [x] 12.1 Add `DialSchedulerClient.getRun(scheduleId, runId)` and a `SchedulerRunResponse` DTO (`id`, `status`, `start_time`, `end_time`, free-form `result`, `conversation_id`); unit tests with `MockRestServiceServer` using the real recorded shape `{"body": {...}, "status_code": 202}`, the URL, and 404 mapping
- [x] 12.2 Add `TestSuiteRunRepository.findStatusesByIds(Collection<UUID>)` (id to status in one query) with its Postgres implementation, and expose it through `TestSuiteRunService`; repository functional test (known ids, unknown ids, empty input)
- [x] 12.3 Add `ScheduleFireRunExtractor` (`service.domain`): run id only when `result.status_code` is 2xx and `result.body.id` is a valid UUID; unit tests with the real sample, a 429 body, a null result, a non-UUID id and a missing body
- [x] 12.4 Extend `ScheduleRunDto` with `testSuiteRunId` and `testSuiteRunStatus` and enrich `TestSuiteScheduleService.listRuns`: detail calls only for `success` fires, bounded concurrency with credential propagation, a cache of finished fires, per-fire failure isolation (warn with the exception, null fields); unit tests for each rule
- [x] 12.5 Functional test in `TestSuiteScheduleTests` (the fake scheduler returns `result`; extend `SchedulerTestServer` to record a fire's response): a fire shows its run id and live status, a deleted run shows a null status, a failed detail call leaves null fields; update the schedule-runs OpenAPI example and run the suites (done: all pass)

## 13. Specs, docs, handoff and final build for tagging and fire history

- [x] 13.1 Flip the new and modified requirements from Planned to Implemented in the delta specs, re-sync the main specs (`suite-run-scheduling`, `test-suite-runs` and `test-suite-runs-query-entity`) and run `openspec validate`
- [x] 13.2 Update `docs/patterns/dial-scheduler.md` (tagging, the ref, naming, the removed non-goal), `docs/key-packages.md` and the AGENTS.md pattern row summary if it changed
- [x] 13.3 Regenerate the front-end handoff with the `fe-api-handoff` skill
- [x] 13.4 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy if a summary became inaccurate (done: index reflects current specs)
- [x] 13.5 Run `./gradlew spotlessApply checkstyleMain checkstyleTest build` (done: build green)
