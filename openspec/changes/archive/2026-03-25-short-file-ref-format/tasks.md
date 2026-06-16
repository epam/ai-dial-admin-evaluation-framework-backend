## 1. FileRefValidator — new component

- [x] 1.1 Create `FileRefValidator` `@Component` in `service.domain` with `validate(ref, suiteId)` method: checks allowed prefix, path-like segments (`[a-zA-Z0-9\-_. ()]`), no `..`, at least one segment after prefix
- [x] 1.2 Add `@ef` ownership check in `FileRefValidator.validateOwnership(ref, suiteId)`: when `suiteId == null`, skip ownership check (create flow — suite UUID not yet assigned); when non-null, validate path starts with `@ef/suites/{testSuiteId}/`
- [x] 1.3 Write unit tests for `FileRefValidator`: valid refs, disallowed prefix, `..` traversal, invalid chars, empty segment, prefix-only, ownership violation

## 2. DialFileRefResolver — format change + new method

- [x] 2.1 Update `resolveToRealPath(ref)` to accept short format (`{prefix}/path`, no `files/` stripping); throw on old `files/` prefix input
- [x] 2.2 Add `resolveToDialRef(ref)` method: calls `resolveToRealPath(ref)` and prepends `files/` to the result
- [x] 2.3 Update `buildEfRef(suiteId, filename)` to return `@ef/suites/{suiteId}/{filename}` (remove `files/` prefix)
- [x] 2.4 Update `DialFileRefResolverTest`: update all fixtures to short format, add tests for `resolveToDialRef()`, add test asserting old `files/` format is rejected

## 3. TestCaseValidationService — delegate to FileRefValidator

- [x] 3.1 Replace inline `files/` prefix check + prefix whitelist logic in `validateFileFields()` with calls to `FileRefValidator`
- [x] 3.2 Update `TestCaseValidationServiceFileTest`: update all file ref fixtures to short format; assert old `files/` format produces a warning

## 4. SuiteValidationService — add file ref validation at suite save

- [x] 4.1 Inject `FileRefValidator` into `SuiteValidationService`
- [x] 4.2 In `validateSuite()`, after binding pass: iterate multipart `FormPartDto` entries; for each with `type == FILE`, call `FileRefValidator.validate(value, suiteId)` and collect warnings
- [x] 4.3 In `validateSuite()` binding pass: when a binding has `constantValue` and the matched template variable carries `|file` type hint, call `FileRefValidator.validate(constantValue, suiteId)` and collect warnings
- [x] 4.4 Write functional tests: suite save with invalid file ref in `FormPartDto` (type FILE) produces warning; suite save with invalid constant binding for `|file` variable produces warning; valid refs produce no warning

## 5. ResolvedRequestService — resolve FILE refs to DIAL format

- [x] 5.1 In `ResolvedRequestService`, make the type hint a capturing group in `PLACEHOLDER_PATTERN` (change `(?:\\|[^:}]+)?` to `(?:\\|([^:}]+))?`; shift default value from group 2 to group 3 in `resolveString()`). In the full-value replacement branch of `resolveObject()`, after calling `templateVariableResolver.resolveVariable()`, check `SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint) && resolved instanceof String` — if true, return `dialFileRefResolver.resolveToDialRef((String) resolved)`. Because `resolveObject()` is already fully recursive, FILE-typed placeholders at any depth in the JSON body are resolved inline.
- [x] 5.2 `FormPartDto` FILE parts must NOT be pre-resolved through `resolveToDialRef()` in `ResolvedRequestService` — leave their resolved values in short format so `MultipartFormDataRequestBodySerializer.addFilePart()` can call `resolveToRealPath()` as it does today. Only JSON and URL-encoded body FILE-typed bindings (identified by `|file` type hint) are pre-resolved.
- [x] 5.3 Update `ResolvedRequestService` tests: assert FILE-typed resolved values contain `files/{realBucket}/...` format; include a test with a FILE-typed placeholder nested inside a JSON object (e.g., `{"outer": {"key": "${{doc|file}}"}}`)

## 6. API contract + OpenAPI

- [x] 6.1 Update `FileMetadataDto.path` `@Schema` example to short format (e.g., `@ef/suites/550e8400-.../document.pdf`)
- [x] 6.2 Update any OpenAPI example JSON files under `src/main/resources/openapi/examples/` that reference file paths in old format

## 7. ZipExportService / ZipImportService — format update

- [x] 7.1 Update `ZipExportService`: stored DIAL refs written into CSV use short format (via updated `buildEfRef()`) — verify no explicit `files/` concatenation remains in export path
- [x] 7.2 Remove the `FILES_PREFIX` constant (value `"files/"`) from `ZipExportService` and replace the `dialRef.startsWith(FILES_PREFIX)` guard with a check against the allowed short-format prefix set (e.g., starts with `@ef/` or `public/`). Without this fix, the guard will always be false after the format change and ZIP export will silently skip downloading file bytes.
- [x] 7.3 Remove `FILES_PREFIX` from `ZipImportService` if present and replace any `startsWith(FILES_PREFIX)` guards with allowed-prefix checks. Note: `FILE_PATH_PATTERN` in `ZipImportService` matches ZIP-internal relative paths (not DIAL refs) and should remain as-is — only the DIAL ref prefix guards need updating.
- [x] 7.4 Update `ZipImportService`: after uploading files to DIAL and building EF refs via `buildEfRef()`, stored CSV values are in short format — verify any remaining `files/` usage aligns with the new format
- [x] 7.5 Update zip import/export tests to use short format file refs

## 8. MultipartFormDataRequestBodySerializer — verify no double resolution

- [x] 8.1 Confirm `MultipartFormDataRequestBodySerializer.addFilePart()` still calls `resolveToRealPath()` (not `resolveToDialRef()`) for the download path — multipart parts are materialized as bytes, not forwarded as refs
- [x] 8.2 Update test fixtures in `MultipartFormDataRequestBodySerializerTest` to short format

## 9. Documentation

- [x] 9.1 Update `openspec/config.yaml` Glossary entry for `file reference` to reflect the new short format: `@ef/suites/{suiteId}/filename` (drop `files/` from the example).
- [x] 9.2 Update `openspec/specs/README.md` to add an entry for the new `file-ref-validation` spec (Status: Planned, one-line summary of the capability)

## 10. Checkstyle + build

- [x] 10.1 Run `./gradlew checkstyleMain checkstyleTest` — fix any violations
- [x] 10.2 Run `./gradlew test` — all tests pass
