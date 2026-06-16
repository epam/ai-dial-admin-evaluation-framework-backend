## Why

When a JSONata expression on a response column returns a single match, the dashjoin JSONata library (per JSONata spec) flattens the result to the bare value instead of a singleton array. The framework then stores that scalar verbatim and binds it as a metric input — so a metric that requires `list[str]` receives `"DECATHLON_map.pdf#page=1"` and the metric service rejects the row with `422 list_type` (issue [#883](https://github.com/epam/ai-dial-admin-backend/issues/883)).

The declared `type` on `ResponseColumnDefinitionDto` (`STRING`, `ARRAY`, …) is currently advisory: extraction never reconciles the JSONata result against it. Single-element-list rows fail; multi-element rows succeed; the user sees an opaque downstream 422.

This change makes the declared column type the contract: extraction either produces a value of the declared shape, or fails the cell with an extraction warning — matching the existing failure mode for invalid JSONata expressions.

## What Changes

- Introduce a new `ResponseColumnTypeReconciler` component (`service.domain`) that takes the raw JSONata result and a `SchemaFieldType` and returns either a reconciled value or throws `TypeMismatchException` (a new domain exception caught by the existing per-column handler in `ResponseColumnExtractor`).
- Reconciliation policy:
  - `ARRAY` + scalar non-null → wrap to singleton array (silent — fixes the reported bug).
  - `ARRAY` + null → null (consistent with the existing "JSONata returned undefined" handling).
  - `ARRAY` + array → as-is.
  - Scalar types (`STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`) + matching JSON type → as-is.
  - Scalar types + safely-parseable JSON value (e.g., `NUMBER` + numeric string, `BOOLEAN` + `"true"`/`"false"`) → coerce silently.
  - `OBJECT` + object/null → as-is.
  - All other shape mismatches → throw `TypeMismatchException` → caught by the existing `ResponseColumnExtractor` catch block → cell becomes `null` and an `ExtractionWarningDto` is appended (existing UX).
- `ResponseColumnExtractor.extract()` calls the reconciler after JSONata evaluation; no other production code changes.
- `FILE` type retains current single-string semantics; multi-file scenarios are expressed via `ARRAY` (no new behavior needed).
- Pre-existing `extracted_columns` rows are left as-is (no migration, no rewrite). Future runs produce reconciled data.

**Not in scope** (deliberately deferred):
- Coercion or warnings inside `BindingResolver` based on the metric's `inputSchema`. This would require threading the metric provider's input JSON Schema into the resolver and is a larger refactor; the response-column fix already eliminates the reported failure mode.
- CSV import path — `csv/SchemaTypeCoercer` does not coerce `OBJECT`/`ARRAY` cells today. A scoped test will document the current behavior; a separate change can address it if test #4 below reveals a parallel bug.

## Capabilities

### New Capabilities
*(none)*

### Modified Capabilities
- `response-columns`: Adds a requirement that JSONata extraction results MUST be reconciled against the declared `type`, defining the coercion table and the warning-on-failure contract.

## Impact

**Code (production):**
- New: `service/domain/ResponseColumnTypeReconciler.java`
- New: `service/domain/exception/TypeMismatchException.java`
- Modified: `service/domain/ResponseColumnExtractor.java` (single call-site addition; existing catch handles thrown mismatches)

**Code (tests):**
- New: unit tests for `ResponseColumnTypeReconciler` (full coercion table)
- New/modified: `ResponseColumnExtractorTest` — singleton-array, mismatch-with-warning, coerce-silently scenarios
- New: functional test in `PostgresFunctionalTests` exercising the metric-evaluation path with a single-element list (regression for #883)
- New: scoped CSV-import test asserting current ARRAY-cell behavior (informational; flags whether a follow-up change is needed)

**APIs:** No request/response DTO changes. `ExtractionWarningDto` payload shape unchanged; new `error` strings appear ("expected ARRAY, got STRING", etc.).

**Database:** No schema changes. No migration. Existing `extracted_columns` JSONB values remain untouched.

**Configuration:** None.

**Documentation:** Update `openspec/specs/response-columns/spec.md` (delta) — extraction-result reconciliation requirement and scenarios. No `docs/configuration.md` or `docs/database-schema.md` changes.

**Behavior change visible to users:** Rows that previously persisted a wrong-shape extracted value (and silently passed it to the metric service) now persist `null` + an extraction warning. The downstream metric error changes from a cryptic provider-side `422` to a `null` field plus a visible warning on the test case run result. This is the intended improvement.

**Scope clarification for #883:** This change fixes #883 only for response columns declared as `type: ARRAY`. Users hitting #883 on a `FILE`-typed column must change the column type to `ARRAY` for multi-file metric inputs; this change does not auto-promote `FILE → list`.

**Risk:** Existing test runs whose JSONata expressions accidentally relied on the mis-shape will start failing more visibly. Mitigation: the warning carries column name + expression + a precise mismatch message ("expected ARRAY, got STRING") — enough to fix the JSONata.
