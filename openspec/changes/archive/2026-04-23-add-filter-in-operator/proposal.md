## Why

The filter system currently only supports single-value equality (`eq`), which means there is no way to match a field against a set of values in a single request. The immediate driver is the bulk DELETE endpoint for test cases: clients need to delete test cases by a list of names, but passing `filter=testCaseName:eq:name1&filter=testCaseName:eq:name2` generates `AND` conditions that can never simultaneously match a single row, silently returning `{"deleted": 0}`.

## What Changes

- Add a new `in` operator to `FilterOperator` that matches a field value against a comma-separated list of values.
- The operator is scoped to `STRING` and `UUID` field types only (boolean, long, and JSONB-numeric fields do not need multi-value set membership).
- `FilterParser` splits the raw value on `,` to produce a list; each element is URL-decoded individually.
- `WhereBuilder` generates `column IN (:paramN)` using Spring's collection parameter binding for STRING, and `CAST(column AS VARCHAR) IN (:paramN)` for UUID columns.
- `FilterWhitelists` adds `IN` to the allowed operators for all `STRING` and `UUID` fields across all existing specs (TEST_CASES, TEST_SUITES, TEST_SUITE_RUNS, EVAL_SUMMARIES, ANALYTICS_RESULTS, METRIC_DECLARATIONS).
- The `entity-filtering` spec is updated to document `in` as a supported operator (promotes the "future extension" item to implemented).

## Capabilities

### New Capabilities
- none (this extends the existing filtering capability)

### Modified Capabilities
- `entity-filtering`: adds `in` operator for STRING and UUID field types to the supported operator set; updates allowed operators on relevant filter whitelists.

## Impact

- **`FilterOperator`** — new enum constant `IN`.
- **`FilterParser`** — new logic to split `in` values on `,` and URL-decode each part; rejects empty lists and blank entries.
- **`FilterCondition`** — `rawValue` holds the original comma-separated string; `parsedValue` holds a `List<String>` when operator is `IN`.
- **`WhereBuilder`** — new branch for `IN` in `buildPredicate()`; binds `List<String>` as collection parameter.
- **`FilterWhitelists`** — `IN` added to allowed operators for all `STRING` and `UUID` field definitions.
- **`FilterParserTest`**, **`WhereBuilderTest`** — new unit test cases.
- **Functional tests** — bulk-delete-by-name scenario on `DELETE /api/v1/test-suites/{id}/test-cases`.
- No DB schema changes. No Flyway migrations. No config changes.
- No breaking changes — existing `eq`/`ne`/`contains` behaviour is unchanged.
