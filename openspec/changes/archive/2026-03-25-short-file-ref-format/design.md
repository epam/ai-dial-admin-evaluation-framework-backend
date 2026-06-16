## Context

File references in the EF API currently use the format `files/{prefix}/path` (e.g., `files/@ef/suites/{id}/file.csv`). The `files/` prefix is an internal implementation detail that leaked into the public API — it mirrors DIAL Core's internal file URL namespace but adds no semantic value for clients. Additionally, file reference validation is scattered and incomplete: `TestCaseValidationService` has inline format logic, `SuiteValidationService` has no file ref validation at all, and `JsonRequestBodySerializer`/`UrlEncodedFormRequestBodySerializer` forward the `@ef` alias unresolved to DIAL (which cannot resolve it). This change fixes all three issues together since they share the same format and validation logic.

## Goals / Non-Goals

**Goals:**
- Drop `files/` from all client-facing file references (API in/out, `buildEfRef()` output)
- Centralize file ref format validation in a single `FileRefValidator` component
- Add validation at suite save time for `FormPartDto` constant values and `|file`-typed template variable constant bindings
- Fix the bug where `@ef` alias is forwarded unresolved to DIAL in JSON/form body payloads
- Resolve FILE refs to DIAL format (`files/{realBucket}/...`) in `ResolvedRequestService` before serializers

**Non-Goals:**
- DB migration for existing `files/@ef/...` stored values (manual cleanup, single env)
- Supporting arbitrary-depth file ref validation or nested JSON path resolution
- Validating file refs embedded in `STRING`-typed schema fields (no type information available)
- Validating `constantValue` bindings when the template variable has no explicit `|file` type hint

## Decisions

### 1. Short format everywhere in API/DB; no boundary transform

**Decision**: The new canonical format is `{prefix}/path` (e.g., `@ef/suites/{id}/file.csv`). Stored values in test case data, `FormPartDto.value`, and `InputBindingDto.constantValue` all use the short format. No add/strip transformation at API boundaries.

**Alternatives considered**:
- *Short format in API only, full format in DB*: would require permanent add/strip translation at every DB read/write path, increasing coupling and maintenance burden.
- *Accept both formats (legacy + new)*: would require branching in `resolveToRealPath()` and `FileRefValidator`, and allows format drift.

**Rationale**: A single canonical format is simpler. The data volume on the single environment is negligible, making a manual cleanup acceptable over permanent dual-format handling.

---

### 2. Two resolution methods on `DialFileRefResolver`

**Decision**: `DialFileRefResolver` exposes two resolution methods with different return formats:
- `resolveToRealPath(ref)` → `{realBucket}/path` — used by `DialFileClient` HTTP calls (URL path appended to `/v1/files/`)
- `resolveToDialRef(ref)` → `files/{realBucket}/path` — used when embedding a file reference as a data value in DIAL deployment request payloads

**Rationale**: `DialFileClient` constructs `PUT /v1/files/{apiPath}` so `resolveToRealPath` must return a path without `files/`. But DIAL expects the full `files/{bucket}/path` format when a file reference appears as a string value inside a JSON or form body sent to a DIAL deployment. The two contexts need different output shapes from the same logical operation.

---

### 3. Resolve FILE refs in `ResolvedRequestService`, not in serializers

**Decision**: `ResolvedRequestService` calls `resolveToDialRef()` on FILE-typed values (both constant and from test case data) when building `ResolvedJsonBodyDto` and `ResolvedUrlEncodedBodyDto`. Serializers receive already-resolved `files/{realBucket}/...` strings for FILE fields.

**Alternatives considered**:
- *Resolve in each serializer*: requires each serializer to carry schema type context and know the resolution API — violates single-responsibility.
- *Carry type metadata through resolved DTOs*: wrapping resolved values in typed wrappers adds complexity and breaks existing DTO contracts.

**Rationale**: `ResolvedRequestService` already has access to binding type hints and `FormPartDto.type`. It is the natural boundary where domain format is translated to DIAL wire format.

**Inline resolution at any depth**: `resolveObject()` is already a fully recursive resolver (handles `Map`, `List`, nested `String`). The type hint is available directly in the placeholder text (`${{var|file}}`). The fix is to make the type hint a **capturing group** in `ResolvedRequestService.PLACEHOLDER_PATTERN` (currently non-capturing, so discarded). Once captured, the full-value replacement branch in `resolveObject()` can check `SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint)` and immediately call `dialFileRefResolver.resolveToDialRef()` on the resolved string value — at the point of substitution, before returning. Because `resolveObject()` recurses into nested maps and lists, FILE-typed placeholders at any depth in the JSON body are resolved automatically. No separate post-resolution pass or `TemplateVariableExtractor` call is needed.

Note: `resolveString()` uses the same pattern for string interpolation — the default value reference must be updated from group(2) to group(3) when capturing the type hint as group(2).

**Multipart FILE parts must NOT be pre-resolved**: `FormPartDto` FILE parts (`FormPartDto.type == FILE`) must NOT be processed through `resolveToDialRef()` in `ResolvedRequestService` — they remain as short-format refs (`@ef/...` or `public/...`) in `ResolvedFormPartDto.resolvedValue`. `MultipartFormDataRequestBodySerializer.addFilePart()` calls `resolveToRealPath()` directly and materializes file bytes. If `ResolvedRequestService` also called `resolveToDialRef()` (prepending `files/`) on multipart parts, then `addFilePart()` would receive `files/{realBucket}/path` and pass that to `resolveToRealPath()`, which throws on `files/` prefix input — a double-resolution bug. Only JSON and URL-encoded body FILE-typed bindings (identified by `|file` type hint) are pre-resolved via `resolveToDialRef()`.

---

### 4. `FileRefValidator` as injectable `@Component` in `service.domain`

**Decision**: A dedicated `FileRefValidator` component owns all file ref format and ownership validation. `TestCaseValidationService` and `SuiteValidationService` both inject and delegate to it.

**Validation rules (short format)**:
- Not blank
- Starts with an allowed prefix (`@ef` alias or `public`)
- At least one path segment after the prefix
- Each path segment matches `[a-zA-Z0-9\-_. ()]`
- No `..` in any segment
- No leading or trailing slash

**Ownership rule** (for `@ef` refs): the path must start with `@ef/suites/{testSuiteId}/` where `testSuiteId` is the owning suite.

**API**: `validate(ref, suiteId)` performs format + ownership validation in one call (ownership check is skipped when `suiteId == null` — create flow). `validateOwnership(ref, suiteId)` is also exposed as a standalone method for callers that need ownership-only checks.

**Rationale**: Inline validation logic in individual services prevents reuse and makes the rules hard to find and update. The project pattern (AGENTS.md) explicitly calls for injectable components for validation/conversion logic.

---

### 5. Suite save validation scope

**Decision**: `SuiteValidationService` validates file refs in:
- `FormPartDto.value` when `FormPartDto.type == FILE` (explicit type declared on the form part)
- `InputBindingDto.constantValue` when the template variable carries a `|file` type hint

**Out of scope**: `constantValue` bindings where the template variable has no `|file` type hint, and constant values in JSON/URL-encoded body request templates (no structural FILE type information available).

**Rationale**: The two in-scope cases have explicit, reliable FILE type signals. Heuristic detection (e.g., "value looks like a file ref") would produce false positives and is not warranted.

**`suiteId` availability and ownership validation**: For `@ef` file reference ownership checks, `suiteId` is available on update (the existing suite UUID). On create, the suite UUID has not yet been assigned — ownership validation SHALL be skipped for create requests and only applied on update. `FileRefValidator.validateOwnership(ref, suiteId)` accepts a nullable `suiteId`: when `suiteId == null`, the ownership check is a no-op; when non-null, the check validates the path starts with `@ef/suites/{suiteId}/`.

## Risks / Trade-offs

- **[Breaking change] Existing clients using `files/@ef/...` format will break** → Acceptable: single internal deployment, no external API consumers. Manual data cleanup.
- **[Stale DB data] Existing test case rows with `files/` prefix will resolve incorrectly** → Mitigation: manual cleanup of existing rows before deploying. `resolveToRealPath()` will throw on `files/` prefix after the change (invalid format).
- **[Test coverage] Existing tests reference `files/@ef/...` format** → All test fixtures and assertions must be updated to short format as part of this change.

## Migration Plan

1. Deploy the code change (no Flyway migration needed).
2. Manually update any stored `files/@ef/...` or `files/public/...` values in the test case data table to strip the `files/` prefix.
3. Verify affected test suites run correctly post-cleanup.

Rollback: revert the deploy; restore DB values from backup if needed (negligible data volume makes this straightforward).
