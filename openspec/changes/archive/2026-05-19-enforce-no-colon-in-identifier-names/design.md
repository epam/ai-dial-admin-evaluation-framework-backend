## Context

CSV export of evaluation summaries (capability `eval-summary-export`) renders column headers in five families, all sharing `:` as the separator:

- `data:<fieldName>` — test case schema fields (`FieldDefinitionDto.name`)
- `response:<columnName>` — response column definitions (`ResponseColumnDefinitionDto.name`)
- `metric:<metricName>:<fieldName>` — metric definition name (`TestSuiteMetricDefinitionRequestDto.name`) + metric output field
- `metricInfo:<metricName>:<fieldName>` — same
- `metricError:<metricName>` — same

The canonical separator lives at `src/main/java/com/epam/aidial/evaluation/constants/EvalSummaryExportColumnConstants.java:17` (`COLUMN_SEPARATOR = ":"`). The column shape is built in `EvalSummaryExportColumnPlanner` (e.g., line 97: `METRIC_COLUMN_PREFIX + metricName + COLUMN_SEPARATOR + fieldName`).

Today, the three name fields have only `@NotBlank` and `@Size(max=255)`; nothing forbids `:`. A name like `"data:foo"` or `"Acc:uracy"` would silently pollute the CSV header, making downstream re-parse ambiguous.

The three name fields share a single shared `TestSuiteRequestDto` between POST and PUT (test-suites controller) and a single `TestSuiteMetricDefinitionRequestDto` between POST and PUT (metric-definitions controller).

## Goals / Non-Goals

**Goals:**
- Reject any name containing `:` at the create/update API boundary with HTTP 400 (`VALIDATION_ERROR`).
- Apply uniformly to POST and PUT — no validation groups, no DTO splitting.
- Keep the rule defined exactly once (one regex + one message in `ValidationConstants`).
- Produce a clear, field-bound error message naming the offending field so client tooling can surface it.

**Non-Goals:**
- No migration / cleanup of pre-existing legacy rows whose names already contain `:`. Owners must rename next time they touch the suite.
- No new restricted characters beyond `:`. The pattern allows everything else — including `.`, spaces, slashes, unicode. (Future restrictions can extend the regex; this change only addresses CSV-export collision.)
- No change to CSV export logic. The export already assumes well-formed names; this change is the upstream gate that makes the assumption hold for future data.
- No retroactive validation of existing rows on read.

## Decisions

### Declarative `@Pattern` on the DTO `name` fields (not service-layer validation)

The rule is a single-field constraint independent of other fields and the same on POST and PUT, so Bean Validation `@Pattern` is the lowest-friction tool:

- Fires before `TestSuiteRequestValidator` and `TestSuiteMetricDefinitionService` reach their existing cross-field checks.
- Cascades automatically through `@Valid` into nested collections (`TestSuiteRequestDto.testCaseSchema`, `TestSuiteRequestDto.responseColumns`).
- Returns field-bound errors via the existing `MethodArgumentNotValidException` handler — no new exception type, no new handler path.

Alternatives considered:
- Adding the check inside `TestSuiteRequestValidator.validateTestSuiteSchemas()` and `TestSuiteMetricDefinitionService.create/update` — rejected because it duplicates the rule across two locations and requires re-running on each update; `@Pattern` covers both with one annotation per field.
- A custom Bean Validation annotation (e.g., `@NoColon`) — rejected as overkill; the constraint is a single regex and not reused elsewhere.

### Regex `^[^:]*$` (allow empty; let `@NotBlank` handle blank)

Layering with the existing `@NotBlank` and `@Size(max=255)`:

- `null` / blank → `@NotBlank` violation (single error, existing message).
- non-blank but contains `:` → `@Pattern` violation (single error, new colon message).
- non-blank, no `:`, ≤ 255 chars → passes all constraints.

Using `^[^:]+$` would emit two violations for an empty string (NotBlank + Pattern), making the API response noisier. `^[^:]*$` keeps each error case shaped by exactly one annotation.

### One shared constant in `ValidationConstants`

Two new public constants:
- `IDENTIFIER_NAME_NO_COLON_PATTERN = "^[^:]*$"`
- `IDENTIFIER_NAME_NO_COLON_MESSAGE = "Name must not contain ':' (reserved as CSV export column separator)"`

Both referenced from each `@Pattern`. Annotation attributes must be compile-time constants, so we cannot directly reference `EvalSummaryExportColumnConstants.COLUMN_SEPARATOR` inside the regex string — instead, document the cross-link in a Javadoc above the constants. Acceptable duplication: the literal `:` appears in two constants files but each carries its own responsibility (export separator vs. validation rule).

### Uniform enforcement on POST and PUT

The shared-DTO design across POST/PUT was the existing project convention. Validation groups would let us scope the rule to POST only, but the user accepted "PUT also enforces" with the trade-off that legacy colon-bearing suites become un-updatable until renamed. This keeps the change to a single annotation per DTO field and avoids splitting DTO contracts.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Legacy suites with colon-bearing names cannot be PUT-updated until renamed. | Error message names the offending field so owners know what to rename. No silent failure — clear 400. No migration ships (user decision). |
| Other identifier types not in scope (e.g., metric output field names emitted by metric scripts) could still contain `:` and break export. | Out of scope here. Those names come from metric implementations, not user input. If needed later, validation can be added at metric-declaration ingest. |
| CSV import that round-trips an export would still need to honor the new convention. | Already implicit — import maps headers back to identifiers via the same separator; forbidding `:` at creation time means valid round-trips for any newly created data. |
| The `data:` prefix collision (e.g., a user names a field `data:x`) is now rejected, but `data` itself (no colon) remains a legal name — could still confuse readers. | Out of scope. Not ambiguous to the export parser. Treat as a docs concern, not a validation rule. |
