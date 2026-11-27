# DIAL scheduler (scheduled suite runs)

Feature-flagged (`dial.scheduler.enabled`, default `false`) cron scheduling of test suite runs, delegated to the
DIAL Core **scheduler application** instead of an EF-side scheduler. EF is a thin manager: it creates and edits
schedules in the scheduler, keeps a small link table so it can list a suite's schedules, and otherwise stays out of
the way. The scheduler fires `POST /api/v1/test-suites/{id}/runs` on this backend, which is the unchanged run endpoint.

Change history and rationale: `openspec/changes/scheduled-suite-runs/` (design) and the
[suite-run-scheduling spec](../../openspec/specs/suite-run-scheduling/spec.md).

## Flow

```
Manage   EF UI -> EF BE  POST /api/v1/test-suites/{id}/schedules
           TestSuiteScheduleService
             1. suite exists?                       (TestSuiteService.existsById)            -> 404
             2. RunConfigValidator.validate         (same rules as createRun)                -> 400
             3. ScheduleRequestBuilder.build        (cron non-empty, IANA timezone)          -> 400
             4. DialSchedulerClient.create ------> {core}/v1/deployments/applications/{appId}/route/v1/schedules/
                  caller's credential, never retried; callback URL carries ?scheduleRef=<EF-generated uuid>
             5. TestSuiteScheduleRepository.save    (schedule_id -> test_suite_id, schedule_ref)
                  on failure: best-effort DialSchedulerClient.delete, then rethrow

Fire     scheduler (cron) -> POST {callback-base-url}/api/v1/test-suites/{id}/runs?scheduleRef=<ref>   {"runConfig": ...}
           authenticated with the credential the scheduler holds for `service-id` (default dial-native)
           TestSuiteRunService.createRun(suiteId, runConfig, scheduleRef)
             ScheduleRefResolver: (ref, suiteId) -> schedule id, else ignored with a WARN   -> run is tagged or untagged
             ScheduledRunNameFormatter: "<testRunName> #<seq>" for a tagged run with a name

History  GET /api/v1/schedules/{id}/runs -> scheduler fire list (no response bodies)
           ScheduleFireRunResolver: detail call per `success` fire -> result.body.id (ScheduleFireRunExtractor)
           TestSuiteRunService.findStatusesByIds: one query for the live status of those runs
```

Everything else (`get`, `update`, `pause`, `resume`, `delete`, `runs`) first loads the link row by schedule id and
answers **404 without calling the scheduler** when there is none, so EF only ever manages schedules it created.

## Why a link table

The scheduler's list response (`ScheduleListItem`) carries no `properties` (so no target URL) and no `metadata`,
and cannot be filtered by either. Without a link, "the schedules of suite X" would need one detail call per
schedule the caller owns. `test_suite_schedules(schedule_id PK, test_suite_id FK ON DELETE CASCADE, created_by,
created_at, schedule_ref)` is the whole local state; cron, run configuration, state and history live only in the
scheduler. See [database-schema](../database-schema.md).

## Run tagging and naming

A run started by a schedule carries `test_suite_runs.schedule_id` so scheduled runs can be listed, filtered and
audited by schedule. The scheduler calls `properties.url` exactly as given, so the tag travels in that URL.

- **The `scheduleRef` is EF's own UUID, not the scheduler's id.** The scheduler's id does not exist until the create
  call returns, so using it would need create-then-PUT (two writes and a window where an early fire is untagged).
  The ref is generated before the create call, sent in the callback URL and stored in
  `test_suite_schedules.schedule_ref` (unique partial index). **Edit keeps the ref**; a schedule created before
  tagging existed gets one on its first edit, so its earlier runs stay untagged.
- **Tagging never blocks a run.** `ScheduleRefResolver` resolves `(scheduleRef, suiteId)` to a schedule id. An unknown,
  blank or other-suite ref is ignored with a WARN and the run is created untagged. Any authenticated caller can pass
  a ref; matching the suite limits spoofing to mislabelling a run in a suite the caller can already run.
- **No foreign key on `schedule_id`.** The tag stays for audit after the schedule is deleted.
- **Query DSL, not the REST list.** `schedule_id` is a plain column of the `test_suite_runs` entity, so clients filter
  with `eq`/`in` there (see [test_suite_runs query entity](test-suite-runs-query-entity.md)). The simple REST list has
  no `scheduleId` filter: its string `eq`/`ne` are case-insensitive and `ne` is not null-safe.
  `TestSuiteRunResponseDto` returns `scheduleId` (omitted when null).
- **Scheduled naming.** A schedule fires with the same `testRunName` every time and `(test_suite_id, test_run_name)` is
  unique, so a tagged run with a provided name becomes `"<testRunName> #<seq>"` (the global run-name sequence, base
  truncated to fit 255). A blank name stays `Run #<n>`; manual runs are unchanged, so a duplicate manual name is still
  409. The sequence is global, so numbers within one schedule are not consecutive.
- **Cycle-free dependencies.** `TestSuiteScheduleService` depends on `TestSuiteRunService`; the run service depends
  only on `ScheduleRefResolver` (its own repository), never on the schedule service.

## Fire history and its EF run

The scheduler's fire list has no recorded response, so a fire is linked to its EF run through the fire detail.

- For a fire that reached EF the recorded `result` is `{"body": <EF's 202 run response>, "status_code": 202}`, so
  `result.body.id` is the run id. `ScheduleFireRunExtractor` accepts it only for a 2xx `status_code` and a valid UUID;
  every other shape (null result, 429 body, non-UUID id) yields no id, because only the success shape is verified.
- **Status is read live from EF** (`TestSuiteRunRepository.findStatusesByIds`, one query), never from the recorded
  body, whose `status` is the `PENDING` seen at fire time. A deleted run keeps its `testSuiteRunId` and shows no status.
- **Cost control.** Only `success` fires can have started a run, so only those get a detail call.
  `ScheduleFireRunResolver` runs them with bounded concurrency (`ScheduleConstants.FIRE_DETAIL_CONCURRENCY`),
  propagating the caller's credential with `TokenPropagationHelper`, and caches finished fires (Caffeine, including
  "no run").
- **Failure isolation.** A failed detail call leaves that fire's run fields null, logs a WARN with the exception and is
  not cached (the next request retries it). It never fails the page.

## Rules worth knowing

- **No retry.** Schedule writes are not idempotent: a retried create would register a second schedule, so
  `DialSchedulerClient` does not use `DialCoreClient.withRetry`.
- **Trailing slash on the collection URL.** The create endpoint is `.../schedules/`. `UriBuilder.pathSegment("")`
  silently drops the slash; the client appends it with `path("/")`. A unit test pins the exact URL.
- **The app id is a path, not a segment.** `public/dial_scheduler__1.0.0` contains a `/`; the client splits it into
  path segments so it is not percent-encoded. A schedule id, in contrast, is expanded as a single encoded segment.
- **`paused` is derived.** The scheduler has no pause flag, only `next_run_time`. `paused = nextRunTime == null`,
  which is also true for a trigger that has ended; `nextRunTime` is returned so clients can see the raw value.
- **Soft delete.** The scheduler deletes softly (`is_deleted`). An `is_deleted=true` schedule is treated like a
  missing one on get and list.
- **List pages the link rows**, then fetches each schedule (one scheduler call per row on the page, sequential).
  A schedule the scheduler no longer has is omitted, so a page can hold fewer items than `size`; `totalElements`
  still counts the link rows.
- **Upstream errors** surface as `DialCoreClientException` and go through the shared `DialCoreErrorMapper`:
  `403` stays `403`; `401`, `404` and `5xx` become `502`; other `4xx` become `400`. So pausing a mapped schedule
  the scheduler has lost answers `502`, not `404` (get, list and delete handle a lost schedule explicitly).
- **Layering.** `web` must not touch `data.db`: the controller passes plain `page`/`size` ints and the service
  builds the `PageRequest` (`LayeredArchitectureTest` rejects the alternative).
- **Request/response shape is read back from free-form JSON.** The scheduler types `trigger` and `properties` as
  objects. `ScheduleCronMapper.fromTrigger` reads `trigger.cron.fields` (or a bare `fields`), and
  `TestSuiteScheduleMapper` reads `properties.payload.runConfig`. These were verified against a fake, not a live
  scheduler; an unknown shape yields an empty cron / null run configuration instead of failing.

## Authentication prerequisite

Two different credentials are involved:

1. **Management calls** (create, edit, pause, ...) carry the **eval caller's own credential** (bearer JWT or
   API key, via the shared caller-credential interceptor). API keys per schedule are not implemented yet.
2. **Fires** use whatever the scheduler holds for `service-id`. EF must accept it on `POST /api/v1/test-suites/{id}/runs`:
   the OIDC issuer for a user token, or `config.rest.security.api-key.enabled=true` for an API key. The caller has
   to be connected to that service in the scheduler for the schedule to fire; EF does not check this at creation.

## Known limitations

- Deleting a **suite** removes its link rows (FK cascade) but not the schedules in the scheduler, which keep firing
  into a 404 until removed there. Cleaning them up on suite deletion is a follow-up.
- Create is two steps (scheduler, then link). If saving the link fails EF deletes the schedule again; if that also
  fails, the schedule can fire without being manageable (logged at WARN).
- Fires are matched to runs through the scheduler's recorded response; if the scheduler stops recording it (or changes
  its shape) the fire list still works and the run fields are simply null.
- The scheduler may not preserve the query string of `url`; the failure mode is untagged runs, never failed runs.
  Verify once on a real DIAL environment (the fake scheduler only covers EF's side).
- Not implemented: per-schedule API key, concurrency policy, local fire history (the scheduler's `runs` are proxied),
  run-now and the sign-in status check the scheduler also offers.

## Where things live

| Concern | Class |
|---------|-------|
| Configuration (`dial.scheduler.*`, fail-fast when enabled without a callback URL) | `configuration.properties.scheduler.SchedulerProperties` |
| HTTP client, no retry, errors as `DialCoreClientException` | `client.scheduler.DialSchedulerClient` (+ `SchedulerClientConfiguration`, `dto/`) |
| Orchestration, link handling, compensation | `service.domain.TestSuiteScheduleService` |
| Scheduler request (URL with `scheduleRef`, payload, timezone) | `service.domain.ScheduleRequestBuilder`, `ScheduleCronMapper` |
| Resolve a callback `scheduleRef` to a schedule id (own repository only) | `service.domain.ScheduleRefResolver` |
| Scheduled run name (`<name> #<seq>`) | `service.domain.ScheduledRunNameFormatter` |
| Fire to EF run id and live status | `service.domain.ScheduleFireRunExtractor`, `ScheduleFireRunResolver`, `TestSuiteRunRepository.findStatusesByIds` |
| Shared run-config rules | `service.domain.RunConfigValidator` (also used by `TestSuiteRunService.createRun`) |
| Scheduler response to REST DTO | `service.domain.mapper.TestSuiteScheduleMapper` |
| Link persistence | `data.db.repository.TestSuiteScheduleRepository` / `PostgresTestSuiteScheduleRepository` |
| REST | `web.controller.TestSuiteScheduleController` (absent when the flag is off) |

## Testing

- `DialSchedulerClientTest` (`MockRestServiceServer`): URLs, bodies, credential headers, error mapping, no retry, fire
  detail (real recorded `{"body": ..., "status_code": 202}` shape).
- `TestSuiteScheduleServiceTest`, `ScheduleRequestBuilderTest`, `ScheduleCronMapperTest`, `RunConfigValidatorTest`,
  `TestSuiteScheduleMapperTest`, `TestSuiteScheduleControllerTest` (standalone MockMvc), `ScheduleRefResolverTest`,
  `ScheduledRunNameFormatterTest`, `ScheduleFireRunExtractorTest`, `ScheduleFireRunResolverTest` (only `success` fires
  fetched, cache, failure isolation, credential propagation, concurrency bound).
- Functional (nested in `PostgresFunctionalTests`): `TestSuiteScheduleTests` runs the real application and
  database against `SchedulerTestServer`, an in-memory fake scheduler in `functional.support` that records every
  request with its credential headers, can inject failures, and returns a fire's recorded `result` on the detail
  endpoint only (like the real scheduler). The tests POST the stored callback URL to EF as the scheduler would, so
  tagging, scheduled naming and fire-history linking run end to end; `SchedulerDisabledTests` covers the flag-off default;
  `TestSuiteScheduleRepositoryTests` covers the link table.
- `OpenApiExamplesTests` runs with `dial.scheduler.enabled=true`: the schedule endpoints and their example files
  only exist in `/v3/api-docs` when the flag is on, and "every example file is injected" would otherwise fail.
