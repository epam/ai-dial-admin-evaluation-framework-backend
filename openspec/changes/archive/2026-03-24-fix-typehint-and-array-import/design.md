## Context

Two bugs were found during end-to-end evaluation runs with metrics.

**Bug A — Type hint breaks binding lookup (HTTP + MCP resolvers)**

`TemplateVariableExtractor` (used by the suite-validation / try-it-out display path) was already correctly updated with the `feat/type-hint-in-param` change to use the pattern:
```
${{([^:|}]+)(?:\|([^:}]+))?(?::([^}]*))?}}
```
Group 1 excludes `|`, so the variable name is captured cleanly (`some_prop`, not `some_prop|file`).

However, the *resolution* side — `ResolvedRequestService` and `McpRequestResolver` — was **not updated**. Both still use the old pattern:
```
${{([^:}]+)(?::([^}]*))?}}
```
Group 1 is `[^:}]+`, which allows `|`. For `${{some_prop|file}}`, group 1 captures `some_prop|file`. The `bindingByVar.get("some_prop|file")` lookup returns null, so the resolved value is null and the property is absent from the outgoing request.

**Bug B — CSV import stores ARRAY/OBJECT cells as strings when schema is empty**

`CsvImportService.parseRow` uses a `fieldTypes` map populated from the *pre-existing* `testCaseSchema`. When the schema is empty (first import, or OVERRIDE mode with no prior schema), `fieldTypes` is an empty map. The ARRAY/OBJECT JSON-parse branch is never reached, so cells containing `["a","b","c"]` pass through `CsvCellParser.parseCell` unchanged — stored as the raw string `"[\"a\",\"b\",\"c\"]"`.

Schema *inference* (`updateInferredTypes`) runs in parallel with parsing and correctly identifies ARRAY type, but the inferred type is not fed back into the same-pass parsing — a chicken-and-egg timing issue. The schema is persisted correctly *after* all rows are written, so subsequent imports (when the schema already says ARRAY) work, but the first-pass data is corrupted.

This causes the metric evaluation job to deliver strings instead of arrays to the metric provider, because `BindingResolver.parseJsonMap` faithfully deserializes the stored string value.

## Goals / Non-Goals

**Goals:**
- Strip `|type` from the captured variable name in `ResolvedRequestService.PLACEHOLDER_PATTERN` and `McpRequestResolver.PLACEHOLDER_PATTERN`, matching the extractor pattern.
- In `CsvImportService.parseRow`, auto-detect and parse JSON array/object cells (`[` / `{` prefix) as structured values when no explicit schema type is known, mirroring the `inferCellType` heuristic.
- In `BindingResolver.resolveSource`, fail fast when a TEST_CASE or RESPONSE binding references a column that does not exist in the data map (distinguish missing key from present-but-null via `containsKey`).

**Non-Goals:**
- Retroactive migration of already-corrupted string arrays in the DB — out of scope; users must re-import affected test cases.
- Changing `CsvCellParser.parseCell` — it is intentionally conservative (no auto-JSON inference) and used in other contexts; the fix stays inside `CsvImportService.parseRow`.
- Any change to the API surface, DTOs, or DB schema.
- Adding fail-fast to `TemplateVariableResolver` — it is used by both validation (soft-fail) and execution paths; the fail-fast fix targets `BindingResolver` only (metric evaluation path).

## Decisions

### D1 — Pattern fix: exclude `|` from group 1, consume `|type` in a non-capturing group

The regex change is minimal:

| Location | Old group 1 | New group 1 | Added non-capturing group |
|---|---|---|---|
| `ResolvedRequestService` | `([^:}]+)` | `([^:|}]+)` | `(?:\|[^:}]+)?` |
| `McpRequestResolver` | `([^:}]+)` | `([^:|}]+)` | `(?:\|[^:}]+)?` |

Default-value group index stays at 2 in both classes; no other code changes needed. `FULL_VALUE_PATTERN` (`^\\$\\{\\{[^}]+\\}\\}$`) already matches type-hinted placeholders correctly — no change needed there.

**Alternative considered**: extract a shared constant `PLACEHOLDER_PATTERN` into a utility. Rejected — would couple two unrelated classes for a one-line fix; the pattern already lives in `TemplateVariableExtractor` as documentation.

### D2 — Auto-detect JSON in `parseRow` when `type == null`

When `fieldTypes.get(b.fieldName())` returns `null` and the raw cell value starts with `[` or `{`, attempt `objectMapper.readValue`. On success, store the parsed object (`List` or `Map`). On parse failure, fall through to the existing `csvCellParser.parseCell` result — **no** `hasJsonParseErrors` flag set (best-effort auto-detect, not a schema-enforced parse).

This mirrors the logic already used in `inferCellType` and keeps the fix self-contained inside `parseRow`'s `"data"` case.

**Alternative considered**: two-pass CSV parsing (first-pass infer, second-pass parse). Rejected — requires buffering or re-reading the stream, contradicts the streaming design, and is significantly more complex.

**Alternative considered**: accept ARRAY/OBJECT strings silently and add coercion in `BindingResolver`. Rejected — moves the fix far from the problem and introduces type-coercion logic in a data-agnostic resolver.

### D3 — Fail-fast in `BindingResolver.resolveSource` for missing columns

`BindingResolver.resolveSource` currently calls `testCaseData.get(columnName)` / `extractedColumns.get(columnName)`, which returns `null` for both "key exists with null value" and "key does not exist". This means a binding that references a non-existent column silently produces `null`, and the metric provider receives a missing parameter without any error signal.

Fix: use `containsKey` to distinguish the two cases. If the key does not exist, throw `IllegalArgumentException`. If the key exists but the value is null, return null (valid — the field was explicitly null in the data).

This applies to `TestCaseBindingSourceDto` (against `testCaseData`) and `ResponseBindingSourceDto` (against `extractedColumns`). `ConstantBindingSourceDto` is unaffected — it always has a value.

`MetricEvaluationWorker` already catches exceptions from the metric evaluation path and records them as error results, so the thrown exception will be caught and surfaced as a per-test-case error rather than crashing the entire run.

**Alternative considered**: add a validation step before the run. Rejected — the run already checks `suite.isValid()`, which covers binding configuration. The missing-column case is a data-level issue (e.g., test case missing a field, or extraction didn't produce expected column) that can only be detected at resolution time.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| A cell starting with `[` or `{` that is intentionally a string (e.g. `[deprecated]`) would be silently parsed as JSON and stored as a list | Only applies when `type == null` (no schema) AND the cell is valid JSON. `[deprecated]` is not valid JSON, so `readValue` throws → falls back to string. Only valid JSON arrays/objects are affected. |
| Existing data with string-stored arrays is not fixed by this change | Document clearly: affected test cases must be re-imported. The metrics job will continue to send strings for existing corrupted rows. |
| Pattern change in `McpRequestResolver` also fixes the inline-interpolation path | Correct and desirable — both the full-value and embedded resolution paths share the same `PLACEHOLDER_PATTERN`, so both are fixed with one change. |
| Fail-fast in `BindingResolver` may surface errors for runs that previously "succeeded" silently | Correct behavior — those runs were delivering null to the metric provider, which is worse than an explicit error. Users can fix data or bindings. |

## Migration Plan

No DB migrations or config changes. Deploy new build — fixed parsing applies to all future CSV imports and all future evaluation runs that use type-hinted placeholders.

## Open Questions

None.
