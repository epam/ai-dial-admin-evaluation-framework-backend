## Why

The eval-runner-job implementation shipped with several bugs and code quality issues that need to be addressed before GA. Critical: HTTP 505 errors from DIAL Core due to HTTP/2 protocol mismatch, a race condition in run cancellation, and an unused timeout parameter. Additionally, the TTFT/TTLT fields add significant complexity across ~25 files with limited user value, and the rate limiting implementation is a naive `Thread.sleep` placeholder.

## What Changes

- **BREAKING**: Remove `timeToFirstTokenMs` and `timeToLastTokenMs` fields from analytics results API, DB schema (rollback migration), DTOs, filters, and all related code (~25 files)
- Fix HTTP 505 errors by pinning `JdkClientHttpRequestFactory` to HTTP/1.1 protocol version
- Fix unused `Duration timeout` parameter in `DialCoreDeploymentInvoker.invokeWithStreaming()` — either apply it or remove it
- Fix race condition in `TestSuiteEvaluationJob.interruptRun()` where cancellation signal is lost if called before async thread registers
- Add `retryCount` (Integer) and `logDetails` (JSONB, nullable) to `TestCaseRunResult` for retry tracking; populate `logDetails` only when `retryCount > 0`
- Store actual `requestBody` in eval results instead of hardcoded `null`
- Replace naive `Thread.sleep(1000/RPS)` rate limiting with Bucket4j token bucket implementation
- Replace custom `resolveInt`/`resolveLong`/`resolveDouble` static methods with `ObjectUtils.defaultIfNull` from Apache Commons Lang
- Delete stale `mock-request-body-builder` spec and clean up outdated mock-related scenarios/requirements from other specs

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `eval-execution-engine`: Fix HTTP client protocol version, apply or remove unused timeout parameter, fix interruptRun race condition, replace Thread.sleep rate limiting with Bucket4j, replace utility methods with ObjectUtils, store requestBody in results, add retry tracking fields, remove TTFT/TTLT capture logic
- `analytics-eval-results`: Remove TTFT/TTLT columns (rollback migration), remove TTFT/TTLT filter entries, add `retryCount`/`logDetails` columns and filter entries, add `requestBody` population, update batch write DTO to accept retry fields
- `test-suite-runs`: Remove TTFT/TTLT from RunConfig-related scenarios if referenced

## Impact

- **API (breaking)**: `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` removed from analytics results read/write DTOs and filter whitelist
- **API (additive)**: `retryCount` and `logDetails` added to analytics results DTOs
- **Database**: New migration to DROP `time_to_first_token_ms` and `time_to_last_token_ms` columns; new migration to ADD `retry_count` and `log_details` columns
- **Dependencies**: Add Bucket4j library to `build.gradle`
- **Specs**: Delete `openspec/specs/mock-request-body-builder/` directory; update `specs/README.md`
- **Code**: ~25 files affected by TTFT/TTLT removal; ~10 files affected by bug fixes and enhancements
