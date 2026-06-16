# Selective Column Projection for TOAST Optimization

When a table has large JSONB columns that aren't needed for list/aggregation queries, use distinct column selection constants in the repository. This avoids PostgreSQL TOAST decompression overhead on bulk queries.

`PostgresEvalSummaryRepository` uses four tiers:
- `SELECT_LIST_COLUMNS` — minimal set for list/count/aggregate (excludes `metric_infos`, `extraction_warnings`, `request_body`, `response_body`)
- `SELECT_EXPORT_COLUMNS` — adds `metric_infos` and `extraction_warnings` for cursor-paginated CSV/preview export reads that don't need bodies
- `SELECT_EXPORT_JOIN_COLUMNS` — same as `SELECT_EXPORT_COLUMNS` plus `request_body`/`response_body` via `LEFT JOIN test_case_run_results`, used by the export path when the body columns are requested. Columns are aliased with `s.` / `r.` because the JOIN is present
- `SELECT_BY_ID_DETAIL_SQL` — full detail query with LEFT JOIN on `test_case_run_results` to fetch `request_body`/`response_body` from a related table; includes all own columns

`PostgresTestSuiteRunRepository` uses two tiers:
- `SELECT_LIST_COLUMNS` — excludes `suite_snapshot` (avoids TOAST decompression on list queries)
- `SELECT_DETAIL_COLUMNS` — includes `suite_snapshot` (used by `findById` only)

The `RecordMapper` maps only the fields present in the fetched `Record`; fields not selected in the query are null in the Record and mapped as null in the domain object.
