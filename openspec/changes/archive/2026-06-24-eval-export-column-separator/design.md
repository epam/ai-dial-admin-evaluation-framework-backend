## Context

The EvalSummary CSV export builds hierarchical column headers as `<family>:<name>` with the colon (`:`) as the family-separator. The separator is defined once in `EvalSummaryExportColumnConstants.COLUMN_SEPARATOR` and concatenated into five prefix constants; `EvalSummaryExportColumnPlanner` builds every header from those constants, and `EvalSummaryExportColumnSelector` matches a client's requested column subset against the planned manifest by **exact string equality** (no parsing/splitting on the separator). Export is write-only — no import or round-trip code parses these headers.

To keep headers unambiguous, three DTOs forbid `:` in user-supplied names via a single shared regex constant `ValidationConstants.IDENTIFIER_NAME_NO_COLON_PATTERN` (`^[^:]*$`):
- `FieldDefinitionDto.name` (test-case schema field)
- `ResponseColumnDefinitionDto.name`
- `TestSuiteMetricDefinitionRequestDto.name`

Investigation revealed the shared constant conflates two distinct reasons:
- **Field names** must avoid `:` because `:` is the **filter operator separator** (`testCaseData.field:op:value` is tokenized on `:` in `FilterParser`). This is unrelated to CSV export and is documented as such in `datasets/spec.md`.
- **Response-column names** and **metric (TSMD) names** avoid `:` solely because it is the **CSV export family-separator**. They are never tokenized in filter expressions — metric values are filtered via dot-path `metricValues.<metric>.<field>` where the name components are bound as SQL parameters, never split on `:`.

## Goals / Non-Goals

**Goals:**
- Change the CSV export family-separator from `:` to `::`.
- Free the single `:` for use in response-column and metric names (relax their validation to forbid only the `::` sequence).
- Preserve correctness of the filter syntax by keeping the strict single-`:` ban on test-case schema field names.
- Keep the validation rationale honest: field-name ban documented as a filter constraint; response/metric ban documented as the export constraint.

**Non-Goals:**
- No change to the CSV **field delimiter** (the comma/semicolon cell separator handled by `CsvDelimiterParser` / the `delimiter` request param) — that is a separate concept.
- No data migration of pre-existing names. Names containing `:` remain valid where now allowed; names containing `::` (none expected) would fail on next update.
- No change to filter syntax, no DB schema / migration / jOOQ regeneration.

## Decisions

**1. Separator value `::` (double colon), changed in one place.**
`EvalSummaryExportColumnConstants.COLUMN_SEPARATOR = "::"`. All five prefix constants derive from it by concatenation, and the planner builds every header from those constants, so the single edit propagates to all composition sites. The selector needs no change (exact-string match works for any separator value).
- *Alternatives considered:* `__` (double underscore) — rejected because underscore is extremely common in real names; reserving it is worse than reserving `::`. Making the separator configurable — rejected as over-engineering for a fixed export contract.

**2. Split the shared validation constant into two.**
- Keep `IDENTIFIER_NAME_NO_COLON_PATTERN` (`^[^:]*$`) + message, re-documented as the **filter operator separator** constraint; used only by `FieldDefinitionDto`.
- Add `IDENTIFIER_NAME_NO_DOUBLE_COLON_PATTERN` (`^(?!.*::).*$`) + message "Name must not contain '::' (reserved as CSV export column separator)"; used by `ResponseColumnDefinitionDto` and `TestSuiteMetricDefinitionRequestDto`.
- The negative-lookahead regex `^(?!.*::).*$` rejects any occurrence of the `::` sequence while allowing single `:`. Empty string still passes (blank handled separately by `@NotBlank`), matching the prior layering.
- *Alternatives considered:* one relaxed constant for all three (rejected — would break filter parsing for field names); keeping `:` banned everywhere (rejected — fails the user's goal of allowing single `:` in names).

**3. Document the asymmetry in the specs.**
The three affected specs each state their own reason. `eval-summary-export` switches the separator to `::`; `response-columns` and `test-suite-metric-definitions` relax to the `::`-only ban; `datasets` (field names) is intentionally left unchanged.

## Risks / Trade-offs

- **[Breaking change to the export column-name contract]** Clients requesting a `columns` subset, or parsing preview/export headers, must switch from `metric:Accuracy:score` to `metric::Accuracy::score`; old-form requests are rejected as unknown columns. → Mitigation: this is the intended contract change; OpenAPI examples and `@Schema` examples are updated in the same change so the documented contract is correct, and the proposal marks it BREAKING.
- **[Validation UX asymmetry]** A single `:` is allowed in metric/response names but rejected in field names. → Mitigation: the field-name error message names the filter-separator reason explicitly, so the rejection is self-explanatory; the asymmetry reflects a real technical constraint, not an oversight.
- **[Stale `::`-bearing names]** Theoretically a name could already contain `::`; it would fail on the next update. → Mitigation: extremely unlikely in practice; no migration performed, consistent with the existing "pre-existing colon names are not migrated" policy already stated in the response-columns / TSMD specs.

## Migration Plan

Pure code + spec + example change; no DB or config migration. Deploy normally. Rollback is a straight revert (no persisted state depends on the separator). After implementation: run `./gradlew spotlessApply`, the affected unit tests, and the EvalSummaryExport functional suite; then `openspec validate` the change before archiving.

## Open Questions

None.
