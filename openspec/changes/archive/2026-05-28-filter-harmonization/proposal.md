## Why

The frontend (GH #1013) sends comparison filters as `field:ge:value` and `field:le:value`, matching the two-letter style of the existing `gt`/`lt`/`eq`/`ne`/`co`/`in` operators. The backend currently exposes the inclusive variants as `gte`/`lte`, so every `ge` / `le` filter from the FE returns `HTTP 400 unsupported operator`. We want the backend operator vocabulary to be internally consistent (all two-letter codes) and to match what the FE already submits, while keeping the old names accepted for a deprecation window so any in-flight FE build or external script keeps working.

## What Changes

- Rename the canonical operator names: `gte` → `ge`, `lte` → `le`. Internally, rename the enum constants `FilterOperator.GTE` → `GE` and `LTE` → `LE`.
- Accept `gte` / `lte` (case-insensitive) as **deprecated aliases** that resolve to `GE` / `LE`. Aliases are parser-only — they do not appear in OpenAPI docs, error messages, or whitelist definitions.
- Auto-generated OpenAPI parameter descriptions (via `QueryParamDescriptionGenerator`) will surface the new names automatically because they derive lowercase strings from the enum constant names.
- Update all illustrative `:gte:` / `:lte:` example strings in `AGENTS.md`, the live OpenSpec specs that reference the operator vocabulary, and the functional test fixtures to use `:ge:` / `:le:`.
- Add a parser test that pins both the canonical names and the alias mapping. Add at least one functional smoke that exercises an alias to lock in backwards compatibility.

**Not breaking for FE**: the FE shipping `ge`/`le` will be unblocked. **Not breaking for existing callers** (during the deprecation window): clients still using `gte`/`lte` continue to work via the alias map.

## Capabilities

### New Capabilities
*(none — this change adjusts existing filter behavior, no new capability surface)*

### Modified Capabilities
- `entity-filtering`: the normative operator list changes from `eq, ne, co, gt, gte, lt, lte, in` to `eq, ne, co, gt, ge, lt, le, in`. New scenarios capture (a) the canonical `ge` / `le` behavior and (b) the deprecated-alias acceptance of `gte` / `lte`. The existing two-level JSONB numeric scenario keeps the same semantics but switches its example to `:ge:`. The "`contains` operator is rejected" scenario is unaffected — `gte`/`lte` are not rejected; they continue to work as aliases.

## Impact

**Production code** (small, mechanical):
- `data.db.model.filter.FilterOperator` — rename two enum constants.
- `service.domain.filter.FilterParser` — add a constant alias map applied in `parseOperator` before `FilterOperator.valueOf(...)`.
- `data.db.repository.sql.WhereBuilder` — rename `case GTE`/`case LTE` in two switch statements; jOOQ call sites (`.ge(...)` / `.le(...)`) are unchanged.
- `data.db.repository.sql.FilterWhitelists` — rename every `FilterOperator.GTE` / `FilterOperator.LTE` reference (the enum is referenced across many entity whitelists; rename is purely mechanical).
- `configuration.QueryParamDescriptionGenerator` — no code change (already derives lowercase via `op.name().toLowerCase`).

**Tests**:
- `data.db.repository.sql.WhereBuilderTest` — rename enum references (compile-fail otherwise).
- `service.domain.filter.FilterParserTest` — add canonical + alias scenarios.
- Three functional tests (`EvalSummaryFunctionalTests`, `TestSuiteRunFunctionalTests`, `TestSuiteFunctionalTests`) — switch example query strings to `:ge:` / `:le:`; add or extend one test that asserts the alias path still works end-to-end.

**Docs & specs**:
- `AGENTS.md` — one example string updated.
- Live specs that reference `gte`/`lte` in example strings: `test-cases`, `test-suites`, `test-suite-runs`, `metrics-storage`, `analytics-eval-results`. Only example strings change; the operator catalog change itself lives in the `entity-filtering` delta in this change.
- Archived changes under `openspec/changes/archive/` are historical records and remain untouched.

**API contract**:
- OpenAPI / Swagger UI will auto-show the new operator names after the enum rename.
- HTTP semantics unchanged: `:ge:` and `:le:` produce the same SQL as `:gte:` and `:lte:` did before; aliased `:gte:` / `:lte:` produce identical SQL to `:ge:` / `:le:`.

**Migration & rollback**:
- No database migration. No configuration changes. No new dependencies.
- Rollback = revert the enum rename + alias entry; behavior reverts to original.

**Deprecation plan** (out of scope for this change, captured for a follow-up):
- The `gte` / `lte` aliases introduced here are a transitional compatibility shim, NOT a permanent part of the operator vocabulary. They MUST be removed in a separate, future OpenSpec change once all known clients (frontend, scripts, integrations) have migrated to `ge` / `le`. That follow-up change will: (a) drop the alias map entries from `FilterParser`, (b) update the `entity-filtering` spec to remove the alias scenarios, and (c) confirm via logs / telemetry that no live caller still submits `gte` / `lte` before merging. A deprecation warning header or log entry MAY be added in an interim change to surface lingering usage.

**Risks**:
- Low. The change is a rename + a one-line alias map, with parser tests and a functional smoke pinning both paths. Highest-risk area is missing a `FilterOperator.GTE` / `FilterOperator.LTE` reference somewhere obscure — compile failure will catch any unchanged callers because the constants no longer exist.
