## MODIFIED Requirements

### Requirement: TestSuiteRunResponseDto structure
The response DTO for a test suite run SHALL include all relevant run information, including the extended `runConfig` with execution and retry settings.
Status: **Implemented**

#### Scenario: Response fields
- **WHEN** system returns a `TestSuiteRunResponseDto`
- **THEN** it SHALL include: `id` (UUID), `testSuiteId` (UUID), `testRunName` (String — user-provided or auto-generated), `status` (String — one of PENDING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED), `runConfig` (object with `numberOfRuns`, optional `testRunName`, optional `execution`, optional `retry`), `numberOfTestCases` (int — finalized at snapshot phase to match `test_case_run_inputs` row count), `startedAt` (Long, nullable, epoch ms — null while the run is PENDING; set when status becomes RUNNING; for a run that ends without ever having been RUNNING it equals `completedAt`), `completedAt` (Long, nullable, epoch ms — set when status becomes COMPLETED, FAILED, or CANCELLED), `errorMessage` (String, nullable — user-friendly error message for FAILED runs), `errorDetails` (object, nullable — structured error info for FAILED runs), `suiteSnapshot` (object, nullable — frozen suite configuration at snapshot phase; null in list results, non-null in detail after snapshot committed), `scheduleId` (String, nullable — id of the schedule that started the run; null for manually started runs and omitted from the JSON when null), `createdAt` (Long, epoch ms), `updatedAt` (Long, epoch ms)

#### Scenario: Error details structure for FAILED runs
- **WHEN** a run has status FAILED and `errorDetails` is non-null
- **THEN** `errorDetails` SHALL contain: `code` (String — machine-readable error code), `category` (String — one of VALIDATION, TIMEOUT, RESOURCE_LIMIT, TEST_SUITE_ERROR, INTERNAL), `message` (String — user-friendly description), `details` (object, nullable — additional context)

#### Scenario: Null fields for non-terminal runs
- **WHEN** a run has status PENDING, RUNNING or CANCELLING
- **THEN** `completedAt`, `errorMessage`, and `errorDetails` SHALL be null

#### Scenario: Start date null only while PENDING
- **WHEN** a run has status PENDING
- **THEN** `startedAt` SHALL be null
- **AND** every run with a status other than PENDING SHALL have a non-null `startedAt`

#### Scenario: Run that ended without executing has startedAt equal to completedAt
- **WHEN** a run ends directly from PENDING — failed by startup reconciliation, failed by a snapshot-phase failure, failed because the DIAL Core route trigger failed, or cancelled while PENDING
- **THEN** its `startedAt` SHALL equal its `completedAt` (zero duration)

#### Scenario: Scheduled run carries its schedule id
- **WHEN** system returns a run that was started by a schedule
- **THEN** `scheduleId` SHALL be that schedule's id, and it SHALL remain after the schedule is deleted

### Requirement: Test run name auto-generation
When `testRunName` is not provided in the run configuration, the service SHALL auto-generate a unique, human-readable name using a monotonically increasing sequence. The sequence SHALL never reuse values, even after run deletion. A UNIQUE constraint on `(test_suite_id, test_run_name)` SHALL be enforced at the database level. A run started by a schedule SHALL always receive a unique name: a provided `testRunName` is used as the base and suffixed with ` #<sequence value>` so that repeated firings do not collide.
Status: **Implemented**

#### Scenario: Auto-generated name when not provided
- **WHEN** client triggers a run without `testRunName` in `runConfig`
- **THEN** system SHALL auto-generate a unique name using a PostgreSQL sequence (e.g., `"Run #42"` where 42 is the next value from the sequence)

#### Scenario: User-provided name used as-is
- **WHEN** client triggers a run that is not started by a schedule, with `testRunName: "My Regression Test"` in `runConfig`
- **THEN** system SHALL use `"My Regression Test"` as the run's `testRunName`

#### Scenario: Sequence survives deletion
- **WHEN** "Run #5" is deleted and a new run is created without specifying a name
- **THEN** system SHALL generate "Run #6" (or the next sequence value), never reusing "Run #5"

#### Scenario: Unique constraint on test suite + name
- **WHEN** client triggers a run with a `testRunName` that already exists for another run in the same test suite
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Same name allowed across different test suites
- **WHEN** two different test suites each have a run named "Run #1"
- **THEN** system SHALL allow both (uniqueness is per test suite, not global)

#### Scenario: Scheduled run with a provided name is suffixed
- **WHEN** a schedule whose `runConfig` has `testRunName: "Nightly"` fires
- **THEN** the run is named `"Nightly #<n>"` where `<n>` is the next sequence value, and a second firing receives a different name instead of failing with HTTP 409

#### Scenario: Over-long scheduled base name is truncated
- **WHEN** a schedule's `testRunName` is long enough that adding the suffix would exceed 255 characters
- **THEN** the base SHALL be truncated so the final name is at most 255 characters

#### Scenario: Scheduled run without a name
- **WHEN** a schedule whose `runConfig` has no `testRunName` fires
- **THEN** the run is named `"Run #<n>"` exactly like a manual run
