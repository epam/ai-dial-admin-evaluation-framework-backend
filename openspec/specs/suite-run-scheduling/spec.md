# Suite Run Scheduling Specification

## Purpose

Runs test suites on a cron schedule without a person starting each run. Scheduling itself is delegated to the DIAL Core
scheduler application: EF creates, edits, pauses, resumes and deletes schedules there, using the eval caller's credential,
and the scheduler fires the existing `POST /api/v1/test-suites/{id}/runs` endpoint on this backend. EF keeps only a
suite-to-schedule link (`test_suite_schedules`) because the scheduler's list response cannot be filtered by suite.
The feature is off unless `dial.scheduler.enabled=true`.

Status: **Implemented**

## Requirements


### Requirement: Scheduling feature toggle and configuration
The system SHALL expose schedule endpoints and the scheduler client only when `dial.scheduler.enabled=true`. When enabled, `dial.scheduler.callback-base-url` MUST be a non-blank value, otherwise the application SHALL fail to start. Defaults SHALL be defined in `application.yml`: `app-id=public/dial_scheduler__1.0.0` (env `SCHEDULER_APP_ID`), `service-id=dial-native` (env `SCHEDULER_SERVICE_ID`), `timeout-seconds=120`.
Status: **Implemented**

#### Scenario: Feature disabled by default
- **WHEN** the application starts without `dial.scheduler.enabled`
- **THEN** no schedule endpoint is registered and requests to `/api/v1/schedules/**` return 404

#### Scenario: Missing callback URL
- **WHEN** `dial.scheduler.enabled=true` and `callback-base-url` is blank
- **THEN** application startup fails with a configuration validation error

### Requirement: Suite-to-schedule mapping
The system SHALL persist, in the meta table `test_suite_schedules`, one row per schedule created through EF with `schedule_id` (primary key), `test_suite_id` (foreign key to `test_suites` with `ON DELETE CASCADE`), `created_by` and `created_at` (epoch milliseconds). Cron, run configuration, state and history SHALL NOT be stored locally. Every schedule operation by id SHALL require a mapping row and SHALL respond 404 for an id without one.
Status: **Implemented**

#### Scenario: Unmapped schedule id
- **WHEN** a caller requests `GET /api/v1/schedules/{id}` for an id that exists in the scheduler but has no mapping row
- **THEN** the response is 404 and the scheduler is not called

#### Scenario: Suite deletion removes the mapping
- **WHEN** a test suite is deleted
- **THEN** its mapping rows are deleted by the foreign-key cascade

### Requirement: Create a suite schedule
`POST /api/v1/test-suites/{suiteId}/schedules` SHALL create a schedule in the DIAL Core scheduler app by calling `POST {core}/v1/deployments/applications/{appId}/route/v1/schedules/` with: `display_name` from the request name, `description` if given, `service_id` from configuration, `timezone` from the request (default `UTC`), `trigger.cron.fields` from the request cron, and `properties` = `{target_type: "rest", method: "POST", url: "{callbackBaseUrl}/api/v1/test-suites/{suiteId}/runs?scheduleRef=<ref>", payload: {runConfig: <request runConfig>}, timeout: <timeout-seconds>}`. The call SHALL carry the caller's credential. `<ref>` is an EF-generated UUID stored with the schedule's link row (see *Runs are tagged with the schedule that started them*). After the scheduler accepts it, the system SHALL insert the mapping row and return 201 with the schedule (id, name, suite id, cron, timezone, runConfig, paused, next run time and created/updated timestamps as epoch milliseconds).
Status: **Implemented**

#### Scenario: Successful creation
- **WHEN** an authenticated caller posts a valid cron and `runConfig` for an existing suite
- **THEN** the scheduler receives the request described above with the caller's credential, a mapping row exists for the returned id, and the response is 201

#### Scenario: Unknown suite
- **WHEN** `suiteId` does not exist
- **THEN** the response is 404 and the scheduler is not called

#### Scenario: Invalid run configuration
- **WHEN** `runConfig` fails the same validation applied by run creation (for example `numberOfRuns` above the configured maximum)
- **THEN** the response is 400 and the scheduler is not called

#### Scenario: Invalid cron or timezone
- **WHEN** the cron has no field set, contains a blank field value, or the timezone is not a valid IANA id
- **THEN** the response is 400 and the scheduler is not called

#### Scenario: Mapping insert fails
- **WHEN** the scheduler accepted the schedule but inserting the mapping row fails
- **THEN** the system attempts to delete the schedule in the scheduler and the request fails

### Requirement: List and get schedules
`GET /api/v1/test-suites/{suiteId}/schedules` SHALL return a page of the suite's schedules: the system SHALL page the mapping rows for the suite (`page`, `size` with configured defaults and maximum) and fetch each schedule from the scheduler. A mapped schedule that the scheduler reports as missing (404) or as deleted SHALL be omitted from the page. `GET /api/v1/schedules/{scheduleId}` SHALL return one schedule, or 404 when the scheduler reports it missing or deleted. Both SHALL use the caller's credential.
Status: **Implemented**

#### Scenario: Only the suite's schedules
- **WHEN** mapping rows exist for suites A and B and the caller lists suite A
- **THEN** only suite A's schedules are returned, with page metadata

#### Scenario: Unknown suite on list
- **WHEN** `suiteId` does not exist
- **THEN** the response is 404

#### Scenario: Externally deleted schedule skipped
- **WHEN** a mapped schedule no longer exists in the scheduler
- **THEN** it is omitted from the list and the request still succeeds

#### Scenario: Soft-deleted schedule treated as missing
- **WHEN** the scheduler returns a mapped schedule flagged as deleted
- **THEN** it is omitted from the list and `GET /api/v1/schedules/{scheduleId}` responds 404

#### Scenario: Paused state
- **WHEN** the scheduler returns `next_run_time = null` for a schedule
- **THEN** the response has `paused = true` and `nextRunTime = null`

### Requirement: Edit a schedule
`PUT /api/v1/schedules/{scheduleId}` SHALL replace name, description, cron, timezone and `runConfig` by calling `PUT .../schedules/{scheduleId}` on the scheduler, rebuilding `properties` for the suite id stored in the mapping row. The suite of a schedule MUST NOT change. The same validations as create apply.
Status: **Implemented**

#### Scenario: Edit cron
- **WHEN** the caller changes the cron of a mapped schedule
- **THEN** the scheduler receives a PUT with the new trigger and the unchanged target URL, and the mapping row is unchanged

### Requirement: Pause, resume and delete
The system SHALL provide `POST /api/v1/schedules/{scheduleId}/pause` and `/resume`, calling the matching scheduler endpoints and returning the updated schedule. `DELETE /api/v1/schedules/{scheduleId}` SHALL delete the schedule in the scheduler (a scheduler 404 SHALL be tolerated) and then delete the mapping row. Already-started runs SHALL NOT be affected.
Status: **Implemented**

#### Scenario: Pause
- **WHEN** the caller pauses an active schedule
- **THEN** the scheduler pause endpoint is called and the response has `paused = true`

#### Scenario: Delete
- **WHEN** the caller deletes a mapped schedule
- **THEN** the scheduler delete endpoint is called, the mapping row is removed, and previously created test suite runs remain unchanged

#### Scenario: Delete when scheduler already lost it
- **WHEN** the scheduler answers 404 on delete
- **THEN** the mapping row is still removed and the response is 204

### Requirement: Schedule fire history
`GET /api/v1/schedules/{scheduleId}/runs` SHALL proxy the scheduler's run history for a mapped schedule, returning each fire's id, status (`success`, `error`, `in_progress`, `missed`) and start and end time (epoch milliseconds), passing through the page parameters.
Status: **Implemented**

#### Scenario: History returned
- **WHEN** the scheduler has fired the schedule twice
- **THEN** both fires are returned

### Requirement: Credential handling and error mapping
The system SHALL use the eval caller's credential (bearer or API key) for scheduler calls via the existing caller-credential interceptor. The system SHALL NOT retry write calls. Scheduler HTTP errors SHALL surface as `DialCoreClientException` and be mapped by the existing exception handler. A scheduler outage SHALL NOT affect any other endpoint.
Status: **Implemented**

#### Scenario: Core rejects the credential
- **WHEN** the scheduler call returns 401 or 403
- **THEN** EF returns the mapped status and error body without retrying

#### Scenario: No retry on write
- **WHEN** a create call fails with 503
- **THEN** exactly one request reaches the scheduler and no mapping row is created

### Requirement: Runs are tagged with the schedule that started them
The system SHALL tag a test suite run with the id of the schedule that started it. The callback URL of every schedule SHALL carry an EF-generated `scheduleRef` query parameter that is stored with the schedule's link row. `POST /api/v1/test-suites/{testSuiteId}/runs` SHALL accept an optional `scheduleRef`, resolve it to the schedule id of a link row whose suite equals `testSuiteId`, and store that id on the run as `scheduleId`. An unknown, blank or other-suite `scheduleRef` SHALL be ignored (the run is created untagged and a warning is logged); a tagging problem SHALL NOT fail run creation. The `scheduleId` SHALL remain on the run after the schedule is deleted. Editing a schedule SHALL keep its `scheduleRef`; a schedule created before tagging existed SHALL be given one on its first edit.
Status: **Implemented**

#### Scenario: Scheduled fire is tagged
- **WHEN** the scheduler calls the callback URL of schedule S for suite X
- **THEN** the created run has `scheduleId` = S and is returned by a Query DSL query on `test_suite_runs` that filters `schedule_id` equal to S

#### Scenario: Manual run is untagged
- **WHEN** a client starts a run without `scheduleRef`
- **THEN** the run's `scheduleId` is null

#### Scenario: Unknown reference is ignored
- **WHEN** a client passes a `scheduleRef` that matches no schedule
- **THEN** the run is created (202) with a null `scheduleId` and a warning is logged

#### Scenario: Reference of another suite is ignored
- **WHEN** a client passes the `scheduleRef` of a schedule that belongs to a different suite
- **THEN** the run is created untagged and the other suite's schedule is not credited

#### Scenario: Tag survives schedule deletion
- **WHEN** a schedule that started runs is deleted
- **THEN** those runs remain and keep their `scheduleId`

#### Scenario: Edit keeps the reference
- **WHEN** a schedule is edited
- **THEN** the callback URL sent to the scheduler carries the same `scheduleRef` as before

#### Scenario: Schedule without a reference gets one on first edit
- **WHEN** a schedule created before tagging existed is edited
- **THEN** a `scheduleRef` is generated, stored and sent in the callback URL

### Requirement: Fire history carries the run each fire started
Each item of `GET /api/v1/schedules/{scheduleId}/runs` SHALL carry `testSuiteRunId` and `testSuiteRunStatus` (both nullable). The system SHALL take `testSuiteRunId` from the scheduler's recorded response of that fire (`result.body.id`) when the recorded `status_code` is 2xx and the id is a valid UUID, and SHALL read `testSuiteRunStatus` at request time from EF's own run record (never from the recorded body). Only fires whose scheduler status is `success` SHALL be resolved. A fire without a run, a run that no longer exists, or a fire whose detail could not be read SHALL show null for the affected field and SHALL NOT fail the page.
Status: **Implemented**

#### Scenario: Successful fire shows its run and the run's current status
- **WHEN** a fire succeeded and its recorded response carries run id R, and run R is now `COMPLETED`
- **THEN** the fire item has `testSuiteRunId` = R and `testSuiteRunStatus` = `COMPLETED`, not the `PENDING` that was recorded at fire time

#### Scenario: Fire that created no run
- **WHEN** a fire has status `error` or `missed`, or its recorded `status_code` is not 2xx, or its recorded body has no valid UUID `id`
- **THEN** both `testSuiteRunId` and `testSuiteRunStatus` are null

#### Scenario: Run was deleted
- **WHEN** the run a fire created has been deleted
- **THEN** the item keeps `testSuiteRunId` and has a null `testSuiteRunStatus`

#### Scenario: Fire detail cannot be read
- **WHEN** the scheduler fails the detail call of one fire on the page
- **THEN** that item has null run fields, the other items are unaffected and the request succeeds

#### Scenario: Only successful fires are resolved
- **WHEN** a page holds fires with statuses `success`, `error`, `missed` and `in_progress`
- **THEN** the scheduler's fire detail is requested only for the `success` fire

## Implementation notes
- `client.scheduler.DialSchedulerClient`; `configuration.properties.scheduler.SchedulerProperties`
- `data.db` `TestSuiteSchedule` model, `TestSuiteScheduleRecordMapper`, `TestSuiteScheduleRepository`; migration `V1.39__CreateTestSuiteSchedulesTable.sql`
- `service.domain.TestSuiteScheduleService`, `ScheduleRequestBuilder`, `ScheduleCronMapper`
- `web.controller.TestSuiteScheduleController`
- Tagging: `data.db` `test_suite_schedules.schedule_ref` and `test_suite_runs.schedule_id` (migration `V1.40__AddScheduleTagging.sql`), `service.domain.ScheduleRefResolver`, `ScheduledRunNameFormatter`, `TestSuiteRunService.createRun(suiteId, runConfig, scheduleRef)`, `ScheduleConstants.SCHEDULE_REF_PARAM`
- Fire history: `DialSchedulerClient.getRun`, `client.scheduler.dto.SchedulerRunResponse`, `service.domain.ScheduleFireRunExtractor`, `ScheduleFireRunResolver`, `TestSuiteRunRepository.findStatusesByIds`
- See design.md and `docs/patterns/dial-scheduler.md`.
