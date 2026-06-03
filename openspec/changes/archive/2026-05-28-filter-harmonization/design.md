## Context

The filter operator vocabulary today is `eq, ne, co, gt, gte, lt, lte, in` — mostly two-letter codes with two odd-ones-out (`gte`, `lte`). The frontend (GH #1013) standardized on the two-letter form (`ge`, `le`) without coordination, so every inclusive comparison filter from the FE now returns HTTP 400. We need to harmonize the BE vocabulary to the FE's expectation without breaking older clients during rollout.

Mechanical context:
- `FilterParser.parseOperator(...)` resolves the raw operator string by `FilterOperator.valueOf(input.toUpperCase(Locale.ROOT))`. The enum constant names ARE the public contract.
- `QueryParamDescriptionGenerator` derives the OpenAPI lowercase strings from `op.name().toLowerCase(Locale.ROOT)`. Renaming the enum automatically updates Swagger docs.
- `WhereBuilder` and `FilterWhitelists` switch on / reference enum constants. Compile failures will catch any unrenamed reference.
- No alias mechanism exists today.

## Goals / Non-Goals

**Goals**
- Make `field:ge:value` and `field:le:value` work end-to-end against every list endpoint that previously accepted `gte` / `lte`.
- Keep `field:gte:value` and `field:lte:value` working as deprecated aliases for one or more release cycles.
- Auto-update Swagger UI to show the new operator names without touching the OpenAPI customizer.
- Lock in the alias behavior with a parser unit test and at least one functional smoke.

**Non-Goals**
- We will NOT change any of the other comparison semantics (`eq`/`ne`/`co`/`in`).
- We will NOT introduce a generic operator-alias framework. The alias map is a tiny `Map.of("GTE", FilterOperator.GE, "LTE", FilterOperator.LE)` constant — overkill abstractions are explicitly out of scope.
- We will NOT remove the aliases in this change. Removal is a follow-up OpenSpec change (called out explicitly in `proposal.md`).
- We will NOT emit a runtime deprecation warning header in this change. (May be added in a follow-up if telemetry shows stragglers.)
- We will NOT touch archived changes under `openspec/changes/archive/`.

## Decisions

### Decision 1 — Rename canonical enum constants

Rename `FilterOperator.GTE` → `FilterOperator.GE` and `LTE` → `LE`.

**Why over alternatives**:
- *Alias-only (keep canonical as GTE/LTE, add `GE`/`LE` constants as additional canonical names)*: would force the OpenAPI docs to list `gte`/`lte` as canonical (since `QueryParamDescriptionGenerator` enumerates the enum), which contradicts the whole point of the change.
- *Free-form string operators*: the typed enum is the seam that wires `WhereBuilder` switches to `FilterWhitelists` allowlists. Replacing it with strings would lose compile-time safety. Rejected.

The rename is mechanical: every `FilterOperator.GTE` / `FilterOperator.LTE` reference in production code, tests, and (transitively) Swagger output flips at once. The compiler enforces completeness — any missed reference is a build break, not a silent runtime regression.

### Decision 2 — Alias resolution lives in `FilterParser`, not `FilterOperator`

`FilterParser.parseOperator(...)` will apply a small alias map *before* calling `FilterOperator.valueOf(...)`:

```
private static final Map<String, FilterOperator> ALIASES = Map.of(
        "GTE", FilterOperator.GE,
        "LTE", FilterOperator.LE);
```

**Why over alternatives**:
- *Enum with `@JsonAlias` or per-constant alias list*: the enum is parsed via `valueOf`, not Jackson — aliases inside the enum would require either a custom factory method or a registry, both heavier than a 3-line map.
- *Add `GTE`/`LTE` back as enum constants*: pollutes the canonical vocabulary. The aliases would leak into `QueryParamDescriptionGenerator`'s output (which iterates `definition.getOperators()`) and into `FilterWhitelists` (which references the enum). We want aliases parser-only, invisible to whitelist/docs.
- *Spring `Converter` from String to FilterOperator*: filter parsing is already a single `parseOperator` method. Adding a Spring converter is two layers of indirection for zero new behavior.

Alias entries are keyed on the already-uppercased normalized form ("GTE" / "LTE"), so the existing case-insensitive parse continues to work uniformly — `Ge`, `GE`, `gte`, `GTE`, etc. all resolve.

### Decision 3 — Aliases are NOT documented in OpenAPI

The whitelist (`FilterWhitelists.FILTERS`) continues to reference only the canonical enum constants (`FilterOperator.GE` / `FilterOperator.LE`). Because `QueryParamDescriptionGenerator` derives operator names from `definition.getOperators()` (the whitelist set), aliases never appear in the auto-generated `gte, lte` rows in Swagger.

**Why**: documenting the aliases would advertise their use and slow down deprecation. They are a compatibility shim. The proposal explicitly schedules their removal in a future change.

### Decision 4 — Test the alias at both unit and functional levels

- `FilterParserTest` (unit): one scenario asserts `parse("field:gte:1")` → `FilterCondition` with `operator == FilterOperator.GE`, mirrored for `LTE` → `LE`. Pinning the canonical enum (not the raw string) guards against a future refactor that re-introduces a distinct `GTE` constant.
- One functional test (extend an existing test in `TestSuiteRunFunctionalTests`, since it already covers both `:gte:` and `:lte:`) asserts that a request using either the new or the old name returns the same result set — locking down full end-to-end behavior.

### Decision 5 — Spec change is a `MODIFIED Requirements` delta on `entity-filtering`

The change to the operator vocabulary lives in a delta spec at `openspec/changes/filter-harmonization/specs/entity-filtering/spec.md`. The existing requirement `Structured filtering via repeatable filter parameter` is copied verbatim and edited to replace `gte` / `lte` with `ge` / `le`, plus new scenarios for the canonical names and the alias acceptance. The two-level JSONB scenario's example is also updated.

The example strings in `test-cases`, `test-suites`, `test-suite-runs`, `metrics-storage`, and `analytics-eval-results` specs are illustrative only — they don't change normative behavior, so a single delta against `entity-filtering` is sufficient. The example refreshes in those spec files are mechanical and happen in `tasks.md`.

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| A stale `FilterOperator.GTE` / `LTE` reference is missed during rename → silent broken behavior | The enum constants are gone — any missed reference is a compile failure, not a runtime regression. |
| Aliases stick around forever because nobody schedules the removal | The proposal explicitly states the aliases MUST be removed in a follow-up OpenSpec change and lists what that change must do. |
| Frontend ships `ge`/`le` before the backend deploys → both sides broken for the gap | Aliases land in this change, so the backend accepts both before/after the FE switches. The deploy order is: backend (this change) first, frontend second. |
| External integrations not yet identified use `gte`/`lte` and break later when aliases are removed | The aliases give a multi-release deprecation window. Before removing, the follow-up change must confirm via logs/telemetry that no live caller still sends the old names (called out in `proposal.md`). |
| `QueryParamDescriptionGenerator` accidentally documents aliases | By construction it can't — it iterates `FilterFieldDefinition.getOperators()`, and the whitelist only contains canonical enum constants. A parser test pins this invariant. |
| Two-level JSONB numeric scenario in `entity-filtering` spec uses `:gte:` example and might be missed | Explicitly listed in the delta spec MODIFIED block and called out in `tasks.md`. |

## Migration Plan

1. **Land this change** (backend) — accepts both `ge`/`le` and `gte`/`lte`.
2. **Frontend ships `ge`/`le`** (GH #1013) — passes against deployed backend.
3. **Verification window** — monitor logs/metrics for any continued `gte`/`lte` usage from non-FE clients.
4. **Follow-up OpenSpec change** — when no live caller still uses `gte`/`lte`, remove the alias map entries and update the `entity-filtering` spec to drop the alias scenarios.

**Rollback**: revert the enum rename and the alias map entry in `FilterParser` (and the delta spec). The change has no DB migration, no config change, and no new dependencies, so revert is a single commit reversion.

## Open Questions

- *Should we emit a structured-log deprecation warning when the alias path fires?* — Deferred. Not required to unblock the FE. Can be added in an interim change if telemetry shows stragglers.
- *Should the alias map be configurable?* — No (resolved). Aliases are tied to a specific historical naming, not a general capability. Configurability would create a long-tail of bespoke renames.
