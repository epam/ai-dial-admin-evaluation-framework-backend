## Why

The current client-facing file reference format (`files/@ef/suites/{id}/file.csv`) exposes an internal `files/` prefix that serves no purpose at the API level and confuses clients. Additionally, file reference format validation is missing in two places: `FormPartDto` constant values (discovered via runtime `ValidationException` on execution) and `InputBindingDto` constant values for `|file`-typed template variables — both silently accept malformed refs at save time and only fail at run time.

## What Changes

- **BREAKING**: Client-facing file reference format changes from `files/{prefix}/...` to `{prefix}/...` (e.g., `@ef/suites/{id}/file.csv` instead of `files/@ef/suites/{id}/file.csv`). All API inputs/outputs use the short format.
- New `FileRefValidator` `@Component` centralizes format + ownership validation (replaces inline logic in `TestCaseValidationService`).
- New `resolveToDialRef()` method on `DialFileRefResolver` returns `files/{realBucket}/path` for embedding file refs as data values inside DIAL deployment request payloads (JSON/form bodies). Fixes existing bug where `@ef` alias was forwarded to DIAL unresolved.
- `SuiteValidationService` gains file ref validation for: `FormPartDto.value` when `type == FILE`, and `InputBindingDto.constantValue` when the bound template variable carries a `|file` type hint.
- `ResolvedRequestService` resolves FILE-typed binding values (both constant and from test case data) to DIAL ref format (`files/{realBucket}/...`) before handing off to serializers — removing DIAL-format knowledge from serializers.
- `buildEfRef()` returns short format `@ef/suites/{id}/{filename}`.
- `FileMetadataDto.path` OpenAPI example updated to short format.

## Capabilities

### New Capabilities
- `file-ref-validation`: Centralized `FileRefValidator` component validating short-format file refs at suite/test-case save time, covering `FormPartDto` FILE parts, `|file`-typed template variable constant bindings, and schema `FILE`-typed test case fields.

### Modified Capabilities
- `dial-file-ref`: Client-facing format changes to `{prefix}/path` (drop `files/` prefix). `resolveToRealPath()` accepts short format. New `resolveToDialRef()` method returns DIAL data-ref format. `buildEfRef()` returns short format.

## Impact

**Code**
- `DialFileRefResolver` — format change + new `resolveToDialRef()` method
- `FileRefValidator` — new `@Component` in `service.domain`
- `TestCaseValidationService` — delegates to `FileRefValidator` (removes inline logic)
- `SuiteValidationService` — adds FILE ref validation for suite definition save
- `ResolvedRequestService` — resolves FILE refs to DIAL format during request resolution
- `JsonRequestBodySerializer`, `UrlEncodedFormRequestBodySerializer` — receive pre-resolved DIAL refs (no code change needed if resolution moves to `ResolvedRequestService`)
- `FileMetadataDto` — updated OpenAPI example
- `DialFileRefResolverTest`, `TestCaseValidationServiceFileTest` — updated for new format; new tests for `FileRefValidator` and suite validation

**API**
- `FileMetadataDto.path` field format changes (breaking for existing clients storing file refs)
- All endpoints returning or accepting file references use the new short format

**Data/Migration**
- Existing test case data containing `files/@ef/...` or `files/public/...` refs is stale after the format change. No Flyway migration — single-environment deployment with negligible data volume; manual cleanup.

**Config**
- No new config properties. `dial.file-storage.bucket-alias` continues to configure the `@ef` alias.
