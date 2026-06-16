## Context

The `eval-summary-export` capability shipped with a column-naming convention that uses `.` as the separator (`data.<f>`, `response.<c>`, `<metric>.<field>`) and bundles all metric-level details into a single `metricInfos` JSON-blob column. In real-world use, analysts pull these CSVs into pandas/polars notebooks and immediately hit two friction points:

1. **Dot collisions.** Metric names (`bert.score`, `precision@5`, `model.v2.accuracy`) and snapshot field names with dots in them produce column headers that are ambiguous to split, and Python's attribute-access shorthand (`df.column`) doesn't work on dot-containing names — every column has to be reached via `df["..."]`.
2. **Per-metric details are buried.** All metric detail objects live in one `metricInfos` JSON blob. Analysts who want recall/precision/F1/MRR detail (e.g. `RecallDetails.facts_ranks`) have to parse JSON at row time, then unnest — losing typed-column ergonomics that DataFrame tooling depends on.

The export and preview endpoints are already implemented (see `EvalSummaryExportColumnPlanner`, `EvalSummaryExportService`, `EvalSummaryExportColumnSelector`, `EvalSummaryExportRow`). The current change is purely about **header shape and per-cell routing** — repository projections, cursor pagination, snapshot resolution, state guards, and CSV serialization rules are all untouched.

## Goals / Non-Goals

**Goals:**

- Replace `.` with `:` as the separator wherever the export embeds a metric or snapshot identifier in a column header.
- Add the `metric:` prefix to value columns so they live in the same `<family>:<name>...` namespace as `data:` and `response:`.
- Flatten the `metricInfos` JSON blob: emit one `metricInfo:<m>:<f>` per top-level `output_schema.properties` key per metric snapshot. Per-field success and per-field error envelopes both land cleanly in their own columns.
- Add a dedicated `metricError:<m>` column per metric — always in the header, populated only when `metricInfos[<m>]` doesn't match a per-field shape (the wholesale-failure case where the metric implementation crashed before emitting a per-field structure).
- Keep the run-frozen column-set rule: column names continue to come from `suite_snapshot` + `run_metric_snapshots`, never from the live `TestSuite` / `TestSuiteMetricDefinition`.
- Preserve OpenAPI examples and preview-manifest semantics — the only contract change is the names and counts of columns.

**Non-Goals:**

- No back-compat shim. Per AGENTS.md ("Avoid backwards-compatibility hacks"), clients that hardcoded `data.foo` / `Accuracy.score` / `metricInfos` headers must update; clients that consume the preview manifest dynamically pick up the new shape transparently.
- No change to identity/execution columns (`id`, `testSuiteId`, …, `responseStatusCode`) or `extractionWarnings` — they aren't derived from snapshot/metric data so the separator convention does not apply.
- No change to repository projections, cursor pagination, snapshot resolution, run-state guards, or the JSON-typed preview path's value-rendering rules.
- No change to the request DTO (`runId` / `computation` / `columns` / `filter` / `delimiter`). Only `@Schema` example strings refresh.
- No new configuration property. No new error code. No DB migration.
- No introduction of a generic "column-name builder" abstraction — the separator is a single project constant; the planner continues to concatenate names inline.

## Decisions

### 1. Separator: `:` (colon)

**Chosen:** `:` everywhere `.` was used today.

**Rationale:**

- Never appears in Python identifiers, so analysts cannot mistake a header for a Python attribute chain.
- Matches the project's existing filter-token syntax (`field:op:value`), keeping a single punctuation idiom across the API surface.
- Does not collide with metric names that contain `.`, `@`, `-`, or `_`.

**Alternatives considered:**

- `__` (double underscore — sklearn's convention for hyperparameter namespaces). Python-identifier-safe and DataFrame-attribute-accessible, but visually noisy, four characters for two columns, and not aligned with the project's filter-token punctuation.
- `/` (path-style). Compact and unambiguous, but consumers commonly use forward-slash for file-path notation in cell values; mixing the two would be confusing.
- Keeping `.` and prefixing with a sentinel character. Doesn't solve the dot-inside-metric-name problem.

### 2. Metric value-column prefix: `metric:`

**Chosen:** `metric:<metricName>:<fieldName>` everywhere the planner emits a metric value descriptor (was `<metricName>.<fieldName>`).

**Rationale:**

- The current convention prefixes inlined snapshot data with `data:` and inlined extracted response columns with `response:`. Without a `metric:` prefix, metric value columns are the only family without a namespace tag — they sit naked in the header next to identity columns like `executionStatus`.
- Future analyst-side scripts that select-by-prefix (`df.filter(pl.col("^metric:.*$"))`) become trivial.

**Alternatives considered:**

- `value:` — accurate for the immediate column family but breaks the symmetry of "`data:` for inputs, `response:` for outputs, `metric:` for evaluated metrics."
- `m:` — shorter, but obscures intent and clashes visually with potential future single-letter families.

### 3. Drop the single `metricInfos` JSON-blob column outright

**Chosen:** Remove the existing single `metricInfos` JSON-blob column entirely. No legacy column survives the migration.

**Rationale:**

- The user explicitly stated they want no legacy blob column; everything that previously lived inside the blob is now reachable via the two new column families (`metricInfo:<m>:<f>` and `metricError:<m>`).
- Keeping the blob alongside the new flattened columns would duplicate data, double the per-row payload size for typical runs, and contradict the AGENTS.md "no backwards-compatibility hacks" rule.

**Alternatives considered:**

- Keep the blob as a fallback for non-routable metricInfos shapes. Rejected — that work is now covered by `metricError:<m>` (decision 5).

### 4. Per-field info columns: one `metricInfo:<m>:<f>` per top-level `output_schema.properties` key

**Chosen:** For each `RunMetricSnapshot`, iterate its `output_schema.properties` (top-level keys only) and emit one `metricInfo:<m>:<f>` descriptor per key. Reuse the existing `OutputSchemaFieldExtractor` — confirmed to iterate only top-level `properties` keys, which matches this requirement exactly (no `$ref` walking, no `oneOf` flattening).

**Cell-value routing:**

| Source                                                                                    | Cell                                              |
|-------------------------------------------------------------------------------------------|---------------------------------------------------|
| `metricInfos[m]` is a JSON object whose keys cover the schema → take `metricInfos[m][f]`  | the field's details payload (object) or empty     |
| `metricInfos[m][f]` is a `{type:"error", message:"..."}` envelope                          | the error envelope (per-field failure)            |
| `metricInfos[m]` is not present, or its keys do not cover the schema                       | empty                                              |

**Rationale:**

- The `OutputSchemaFieldExtractor` is already battle-tested for the value-column flattening — reusing it guarantees the info columns and the value columns are emitted in lockstep (same iteration order, same key set).
- The per-field error envelope is part of the `oneOf [Field, MetricError]` discriminator that the metric subsystem already produces; this routing decision keeps per-field failures local to the affected field's column instead of polluting the wholesale-error column.

**Alternatives considered:**

- Walk `oneOf`/`$ref` to extract the inner `details` schema's shape. Rejected as scope creep — the export's job is to expose what's there per top-level property, not to expand nested schemas.
- Emit `metricInfo:<m>` (per-metric blob) instead of per-field. Rejected — defeats the flattening goal; analysts still have to parse JSON.

### 5. Wholesale-failure column: `metricError:<m>`, always in header

**Chosen:** Add one `metricError:<m>` column per `RunMetricSnapshot`, always present in the header manifest, populated only when `metricInfos[<m>]` cannot be interpreted as a per-field map.

**Routing rule (executed per row, per metric):**

```
let info = metricInfos[m];
if (info is a JSON object AND ∃ key k ∈ info.keys such that k ∈ output_schema.properties.keys)
    → treat info as the per-field map; populate `metricInfo:<m>:<f>` cells from info[f]; leave `metricError:<m>` empty
else
    → route the whole `info` payload to `metricError:<m>`; leave all `metricInfo:<m>:<f>` cells empty
```

**Rationale:**

- A metric can fail in two distinct ways: per-field (one field's calculation crashed but others succeeded — the standard discriminated-union case) or wholesale (the metric implementation crashed before producing per-field structure, leaving an opaque error blob keyed only by metric name). The per-field error case is already covered by decision 4. This decision covers the wholesale case without losing the failure information.
- Always-in-header (rather than emitted on-demand per row) is required because the column manifest is fixed before the data scan starts — the CSV writer can't add columns mid-stream, and the preview manifest must list every column the export endpoint can emit.
- "Empty when not needed" is consistent with the existing CSV cell-serialization rule (null → empty string).
- The "at least one key matches the schema" test is the cheapest sufficient check: if the metric impl produced any per-field shape, even a partial one (e.g. only `recall` populated but `precision`/`f1`/`mrr` missing), the per-field columns are the right home for the data; if zero keys overlap, the impl never reached the per-field shape and the whole payload is by definition wholesale-error material.

**Alternatives considered:**

- A single `metricError` column (not per-metric). Rejected — collapses errors from multiple metrics into one cell, losing the ability to filter or sort by which metric failed.
- Emit `metricError:<m>` only when the routing rule says the column needs to be populated. Rejected — manifest is decided before the data scan, and conditional emission per row would require a two-pass scan.
- A `_meta` or `error` JSON blob column. Rejected — same JSON-parsing friction the change is trying to eliminate.

### 6. Header order

The header order in the produced CSV (and the preview manifest's headers array) shall be:

```
<identity/execution columns>           ← unchanged: id, testSuiteId, …, responseStatusCode
data:<f1>, data:<f2>, …                 ← snapshot testCaseSchema order
response:<c1>, response:<c2>, …         ← snapshot responseColumns order
[for each metric snapshot, in snapshot order:]
  metric:<m>:<f1>, metric:<m>:<f2>, …                 ← output_schema.properties order
  metricInfo:<m>:<f1>, metricInfo:<m>:<f2>, …       ← same field order
  metricError:<m>
extractionWarnings                       ← unchanged
[requestBody, responseBody]              ← unchanged: emitted at the tail by the planner, gated by columns subset
```

**Rationale:** Metric blocks are kept adjacent (value columns + info columns + error column for the same metric land in three contiguous groups), keeping analyst-side column-pick scripts simple. The identity / `data:` / `response:` / metric / `extractionWarnings` / bodies macro-ordering is unchanged from today.

### 7. Where the separator lives in code

**Chosen:** Single `COLUMN_SEPARATOR = ":"` constant on a new `EvalSummaryExportColumnConstants` class under `com.epam.aidial.evaluation.constants`. Three additional sibling constants for the per-family prefixes: `DATA_COLUMN_PREFIX = "data" + COLUMN_SEPARATOR`, `RESPONSE_COLUMN_PREFIX = "response" + COLUMN_SEPARATOR`, `METRIC_COLUMN_PREFIX = "metric" + COLUMN_SEPARATOR`, `METRIC_INFO_COLUMN_PREFIX = "metricInfos" + COLUMN_SEPARATOR`, `METRIC_ERROR_COLUMN_PREFIX = "metricError" + COLUMN_SEPARATOR`.

**Rationale:**

- Matches the project's "constants per bounded context" rule (AGENTS.md).
- A single edit changes the separator should a future requirement appear; no scattering of `":"` literals across the planner.
- Per-family prefix constants let test fixtures and OpenAPI examples reference the same source of truth instead of restating the convention.

**Alternatives considered:**

- Inline `":"` literals at the three concatenation sites (the today-pattern with `"."`). Rejected — already considered noise; centralizing now avoids a follow-up refactor when the separator inevitably needs to change again.
- Put the constants on `ValidationConstants`. Rejected — those govern validation caps; export column naming is a different bounded context.

### 8. Row-side accessors on `EvalSummaryExportRow`

**Chosen:** Add two new methods to the row wrapper that already exposes `metricValues()` and `metricInfos()`:

```java
JsonNode metricInfo(String metricName, String fieldName);
JsonNode metricWholesaleError(String metricName);
```

The routing rule (decision 5) lives inside these accessors, parameterized by the row's resolved `output_schema.properties` key set. `EvalSummaryExportColumnPlanner` passes a `Set<String>` of per-metric field names into the descriptor closures it builds, so the row accessors can do the membership check without re-reading the snapshot.

**Rationale:**

- Keeps the planner descriptors thin — each `metricInfo:<m>:<f>` descriptor closes over `(m, f)` and calls `row.metricInfo(m, f)`; each `metricError:<m>` descriptor closes over `(m, fieldKeys)` and calls `row.metricWholesaleError(m)`.
- Routing logic lives in one place; both the CSV path and the preview path benefit transparently.
- Returning `JsonNode` (not `Object`) keeps `EvalSummaryExportService.formatCsvCell` and the preview path's typed-value renderer working unchanged.

**Alternatives considered:**

- Build the routing into the planner descriptors directly. Rejected — descriptor closures would each capture both the field-key set and the per-row navigation logic, duplicating it `N` ways per row.
- Add a dedicated `MetricInfoRouter` `@Component` and inject it into the row wrapper. Rejected — overkill for two ~10-line accessors with no Spring dependencies.

### 9. Planner-output cap (`MAX_EXPORT_COLUMNS`)

The existing `ValidationConstants.MAX_EXPORT_COLUMNS` check fires **after** planning, so the wider manifest produced by this change is bounded by exactly the same cap. No constant change is needed. The cap's failure scenario in the existing spec ("Planner output column count limit") covers this change automatically — a run with enough metrics × fields to overflow will fail planning with `VALIDATION_ERROR` regardless of which families overflowed.

### 10. Preview path

The preview endpoint reuses the same column descriptors as the CSV path; it differs only in how the cell values are serialized (typed JSON values vs. CSV strings). The new descriptors return `JsonNode` from the row accessors, which the preview path emits as nested objects (the existing "Cells preserve JSON types" scenario). No preview-specific work beyond fixture / example refreshes is required.

## Risks / Trade-offs

- **[Wider manifests can hit `MAX_EXPORT_COLUMNS` sooner.]** For a metric with `K` fields, this change emits `K + K + 1 = 2K + 1` columns (was `K + 1` shared blob). Runs with many metrics × many fields are now closer to the cap. → Mitigation: the planner-output cap already fires `HTTP 400 VALIDATION_ERROR` with a precise count vs. cap message, so this is a visible, actionable failure rather than a silent overrun. If users hit it, raise the cap in `application.yml` or use the explicit `columns` subset to project only what they need.
- **[Downstream consumers must update.]** Any client that hardcoded `data.foo`, `Accuracy.score`, or `metricInfos` will fail to find columns after this lands. → Mitigation: per AGENTS.md no shim is added; clients are expected to read the preview manifest dynamically (the typed `headers` array is the discovery mechanism, unchanged). The proposal Impact section names this risk explicitly. The change document is the migration signal.
- **[Routing-rule false positives — schema field whose name accidentally matches a wholesale-error envelope key.]** A metric's `output_schema.properties` could in principle include a top-level key `type` or `message`, matching the keys produced by `{type:"error", message:"..."}`. The routing rule would then misclassify a wholesale-error payload as a "per-field map." → Mitigation: low likelihood (no current metric uses these names; well-formed wholesale-error envelopes carry both `type` and `message`, neither of which is a typical metric field name). If observed in production, the rule can be tightened to require ≥2 overlaps or to exclude reserved envelope-key names.
- **[Per-row routing cost.]** Two new computations per row per metric (the set-membership check + the per-field cell extraction). → Mitigation: the field-key set is built once per metric snapshot during planning and reused per row; per-row work is `O(fields_per_metric)`, negligible against the existing JSONB deserialization cost.
- **[Reduced cardinality test for old runs.]** Existing functional tests assert specific CSV header lines and specific cell positions, all of which change. → Mitigation: tests refresh in the same change (called out in tasks.md); the test build cycle will surface any missed assertion.

## Migration Plan

1. **Land the change.** Single PR introduces the constants class, the row accessors, the planner edits, the OpenAPI example refreshes, and the test updates. No DB migration, no config change.
2. **Communicate the breaking change.** Mention in release notes that the eval-summary export CSV header has changed shape (separator `:` replaces `.`; metric value columns are now prefixed; `metricInfos` blob is replaced by per-field and per-metric-error columns).
3. **Rollback strategy:** revert the PR; no data is mutated by this change, so revert is purely a code-deployment operation.

## Open Questions

_None._ All routing-rule edge cases and structural decisions were locked in during the explore phase.
