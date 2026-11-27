## Why

Test suite runs can only be started manually by an authenticated user (issue #32). That blocks regression monitoring over time and unattended runs (overnight, weekends, after deploys). DIAL Core already ships a scheduler application that fires HTTP calls on a cron, so EF can delegate the scheduling engine instead of building its own.

## What Changes

- Delegate scheduling to the **DIAL Core scheduler app**; EF does not run a scheduler. EF keeps only a small **mapping table** `test_suite_schedules` (`schedule_id` → `test_suite_id`), because the scheduler's list response carries no target URL and cannot be filtered by suite. The scheduler remains the source of truth for cron, state and history.
- New Flyway meta migration `V1.39__CreateTestSuiteSchedulesTable.sql` (next after V1.38), jOOQ regeneration (`./gradlew generateJooq`), `docs/database-schema.md` update.
- New Flyway meta migration `V1.40__AddScheduleTagging.sql`: nullable `test_suite_runs.schedule_id` (no foreign key) and nullable `test_suite_schedules.schedule_ref`; jOOQ regeneration; `docs/database-schema.md` update.
- **Run tagging** (acceptance criterion of issue #32: runs from a schedule are tagged with the schedule id so they can be listed, filtered and audited): a run started by a schedule stores the schedule id in `test_suite_runs.schedule_id`, returns it as `scheduleId` on run responses, and can be filtered by it through the Query DSL `test_suite_runs` entity (`schedule_id`), which publishes the column with no extra wiring. The simple REST list (`GET /api/v1/test-suite-runs`) returns the field but gets no `scheduleId` filter. The schedule's callback URL carries an EF-generated `scheduleRef` query parameter that the run endpoint resolves to the schedule id; it is honoured only for a schedule of that suite and otherwise ignored, so tagging can never block a run. The tag has no foreign key and survives deleting the schedule.
- **Scheduled run naming**: `test_suite_runs` has a unique `(test_suite_id, test_run_name)`, so a schedule with a fixed `runConfig.testRunName` would fail on every fire after the first. For a scheduled run only, a provided `testRunName` is suffixed with ` #<sequence>` (blank names stay `Run #<n>`); manual runs are unchanged.
- **Fire history carries the run it started**: a scheduler fire's own status only says that the call to `POST /runs` was accepted (EF answers 202 immediately), so it says nothing about the evaluation. `GET /api/v1/schedules/{id}/runs` items gain `testSuiteRunId` and `testSuiteRunStatus`. The run id is read from the scheduler's recorded response of the fire (`result.body.id`, verified against a real fire: `{"body": <EF's 202 run response>, "status_code": 202}`), and the status is read live from EF's own run table. Fires without a run (rejected, errored, still in progress) carry null for both.
- New REST endpoints (Implemented by this change):
  - `POST /api/v1/test-suites/{suiteId}/schedules` — create a cron schedule that runs the suite.
  - `GET /api/v1/test-suites/{suiteId}/schedules` — list the suite's schedules (paged by EF from the mapping table).
  - `GET | PUT | DELETE /api/v1/schedules/{scheduleId}` — get, edit, delete.
  - `POST /api/v1/schedules/{scheduleId}/pause` and `/resume`.
  - `GET /api/v1/schedules/{scheduleId}/runs` — scheduler-side fire history.
  - Only schedules in the mapping table are manageable through EF; any other id is 404.
- On create/edit EF builds the scheduler request: `target_type=rest`, `method=POST`, `url={callbackBaseUrl}/api/v1/test-suites/{suiteId}/runs`, `payload={"runConfig": ...}`, `service_id` from config (default `dial-native`), `timezone` (optional, default `UTC`), `trigger.cron.fields` from the request. The suite and `runConfig` are validated up front (400/404).
- New outbound client `DialSchedulerClient` calling `{DIAL_URL}/v1/deployments/applications/{appId}/route/v1/schedules/...` with the **eval caller's credential** (existing `callerCredentialInterceptor`). Writes are never retried. Per-schedule API keys are deferred.
- New configuration (docs/configuration.md updated): `dial.scheduler.enabled` (`DIAL_SCHEDULER_ENABLED`), `app-id` (`SCHEDULER_APP_ID`, default `public/dial_scheduler__1.0.0`), `callback-base-url` (`SCHEDULER_CALLBACK_BASE_URL`, required when enabled), `service-id` (`SCHEDULER_SERVICE_ID`, default `dial-native`), `timeout-seconds` (`SCHEDULER_TIMEOUT_SECONDS`, default 120).
- Feature is off by default; controller, client and service beans are `@ConditionalOnProperty(dial.scheduler.enabled)`.

**Goals:** cron-scheduled suite runs; pause/resume/edit/delete; per-suite listing; reuse existing run creation, validation and auth.
**Non-goals / Planned (not in this change):** per-schedule encrypted API key, concurrency skip/queue policy, local last/next-fire and skip-reason history, notifications, non-cron recurrence, run-now (`POST /{id}/run`) and sign-in status check (both available in the scheduler API, deliberately left out), deleting a suite's schedules in the scheduler when the suite is deleted.

## Capabilities

### New Capabilities
- `suite-run-scheduling`: manage cron schedules for test suite runs through the DIAL Core scheduler app (create, list per suite, get, edit, delete, pause, resume, fire history), including the suite→schedule mapping, configuration, validation and credential handling.

### Modified Capabilities
- `test-suite-runs`: the run response gains `scheduleId`, and run-name generation gains the scheduled-run rule. Manually started runs behave exactly as before.
- `test-suite-runs-query-entity`: the entity field set gains `schedule_id`.

## Impact

- **New packages/classes:** `client.scheduler` (`DialSchedulerClient`, DTOs, client configuration), `configuration.properties.scheduler.SchedulerProperties` (done), `data.db` model/RecordMapper/repository for `test_suite_schedules`, `service.domain.TestSuiteScheduleService` + MapStruct mapper + DTOs, `web.controller.TestSuiteScheduleController`.
- **APIs:** new endpoints above; OpenAPI annotations and examples under `src/main/resources/openapi/examples/`.
- **Database:** new table `test_suite_schedules` (meta), Flyway `V1.39__CreateTestSuiteSchedulesTable.sql`; FK `test_suite_id → test_suites(id) ON DELETE CASCADE`; index on `test_suite_id`. Generated jOOQ sources committed.
- **Run tagging data:** V1.40 adds nullable `test_suite_runs.schedule_id VARCHAR(255)` (no FK, partial index on `(schedule_id, created_at_ms DESC)`) and nullable `test_suite_schedules.schedule_ref VARCHAR(36)` (unique partial index). Adding nullable columns is instant in PostgreSQL; existing rows stay valid.
- **Shared DTO:** `TestSuiteRunResponseDto` lives in `evaluation-runner-core` and is also consumed by `eval-cli`, so the new nullable `scheduleId` field touches both modules' tests.
- **Fire history cost:** the scheduler's fire list omits `result`, so enriching a page costs one scheduler call per successful fire on it; the design bounds this (only successful fires, bounded concurrency, cache of finished fires, failures isolated per fire).
- **Config/docs:** `application.yml`, `docs/configuration.md`, `docs/database-schema.md`, new `docs/patterns/dial-scheduler.md`, AGENTS.md pattern row, `docs/key-packages.md`.
- **Security:** EF forwards the caller's bearer/API key to Core. When the schedule fires, the scheduler calls `POST /api/v1/test-suites/{id}/runs` with the credential it holds for `service_id`; EF must accept that credential (OIDC issuer or `config.rest.security.api-key.enabled`).
- **Risks:** deleting a suite cascades the mapping row but leaves the schedule in the scheduler, which then fires into 404 until removed there (documented, follow-up); the scheduler has no `paused` flag, so paused is derived from `next_run_time == null`; create is two-step (scheduler, then mapping) with best-effort compensation.
- **Rollout:** ship disabled; enable per environment by setting `DIAL_SCHEDULER_ENABLED=true` and `SCHEDULER_CALLBACK_BASE_URL` (+ `SCHEDULER_APP_ID` if non-default). The migration runs regardless of the flag.
- **Test plan:** unit tests for client (MockRestServiceServer), service and mapper; repository and functional tests (Testcontainers) with a stubbed scheduler; context-boot functional test with the flag on and off.
