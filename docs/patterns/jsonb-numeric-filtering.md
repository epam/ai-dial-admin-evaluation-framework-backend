# JSONB_NUMERIC Multi-Level Path Filtering

`FilterFieldType.JSONB_NUMERIC` enables filtering on numeric values nested two levels deep in JSONB columns. Used for `metric_values` in eval summaries (e.g., `metricValues.Accuracy.score:gte:0.8`).

- `WhereBuilder` splits the field's jsonbKey on the first dot to produce two parameterized path components
- Generated SQL: `(column->:key1->>:key2)::numeric <op> :valueParam` — path components are bound as named parameters (not quoted literals) to prevent SQL injection
- Values are parsed as `BigDecimal` to avoid IEEE 754 precision loss when compared against PostgreSQL `numeric`
- Only two-level paths are supported; deeper nesting is rejected
- Existing `JSONB_STRING` single-level behavior is unchanged
