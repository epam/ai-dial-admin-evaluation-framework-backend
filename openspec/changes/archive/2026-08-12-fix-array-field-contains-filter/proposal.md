# Fix array-field CONTAINS in run conditions (GH #142)

## Why

A suite run condition of "array field **Contains** value" makes run creation fail with an unhandled HTTP 500 —
reported in the issue as a 403, because the `/error` dispatch is not public and `anyRequest().denyAll()`
denies it. The Admin UI
stores the "Contains" condition with its left operand wrapped in the case-normalizing `lower` function
(`co(lower(data::<arrayField>), "tee")`). `FilterTranslator` only routes `co`/`nc` to JSONB array-element
containment when the left operand is a **bare** array-typed field, so a wrapped operand falls through to the
scalar `LIKE` path and `lower` is applied to a raw JSONB path — `lower(jsonb)` does not exist in Postgres
(SQLSTATE 42883). The filter is accepted at suite write time (translation succeeds; nothing renders or
executes the SQL), so the failure only surfaces when the run is created and the runnable-case count query
executes (`TestSuiteRunService.createRun` → `RunnableTestCaseCounter` → `QueryDslRunnableTestCaseSelector`).
Users cannot run any suite whose condition filters an array field.

## What Changes

- `co`/`nc` **see through a case-normalizing wrapper** on an array-typed field: when the left operand is
  `lower(<bare array field>)` or `upper(<bare array field>)`, the wrapper is discarded and the comparison
  translates to **case-insensitive whole-element JSONB containment** instead of `LIKE`. The wrapper is the
  only thing the client can be expressing by applying `lower`/`upper` to an array (the function is undefined
  on `jsonb`), so it is read as "match ignoring case".
- Matching semantics stay **whole element**, consistent with the existing bare-field containment: for value
  `"tee"`, an element `"Tee"` matches, an element `"tee-shirt"` does not. Elements are compared by their
  JSON text rendering, so — unlike the bare-field form — a string operand also matches a non-string element
  with the same rendering (`"1"` matches the element `1`); see design.md decision 2a.
- Under the case-insensitive form, a row whose array-declared field holds a non-array value (or no value)
  does not match and does not break the statement — a second deliberate divergence from the bare-field
  form, where `?` matches a string value equal to the operand and an object value carrying it as a key.
- The wrapper name is recognized ignoring case (`LOWER` routes like `lower`), matching how the function
  registry resolves names; the two spellings live in `QueryFunctionNames`, shared with the catalog.
- Bare-field `co`/`nc` on an array field is **unchanged** (case-sensitive element existence via the JSONB `?`
  operator), as are `co`/`nc` on scalar fields and every other operator. No wrapper unwrapping happens
  outside the array `co`/`nc` branch.
- No wire-contract change: the same filter JSON the UI already sends starts working. No new endpoint, DTO,
  config property, or DB migration.

### Not in scope

- Operand type-checking for the function catalog (`lower`/`upper`/`trim`/`length`/`abs` cast their argument
  without checking it). Only the `co`/`nc` array branch is taught to read the wrapper, so every other shape
  that puts a string function over a JSONB-backed field still renders `lower(jsonb)` and still fails the
  same way — explicitly including `lower(<object field>)`, `eq`/`ne`/`in`/`lt`/`gt`/`le`/`ge` over
  `lower(<array field>)`, and nested wrappers such as `trim(lower(<array field>))`. On the run-creation
  path these surface as an unhandled 500, reaching the client as 403 in oidc mode (no `BadSqlGrammarException`
  mapping exists outside `StructuredQueryExecutor`). Tracked separately; this change fixes the reported filter, and the boundary
  is recorded here so the next such report is triaged as a known gap rather than a regression.
- Translating the DB error on the run-creation path to a 4xx (the `/queries/execute` path already does this in
  `StructuredQueryExecutor`; run creation returns a raw 500, surfaced as 403 in oidc mode).
- Substring-across-elements semantics for array `co`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `structured-query-model`: the **SQL translation** requirement changes — `co`/`nc` on an array-typed field
  now also cover a `lower`/`upper`-wrapped operand, translating to case-insensitive whole-element
  containment; the existing "Contains on a non-array left operand falls through to LIKE" scenario is narrowed
  to exclude that wrapper.

## Impact

- **Code**: `experimental.query.service.translate.FilterTranslator` (array-containment branch, wrapper
  unwrapping, case-insensitive containment predicate) plus a new
  `experimental.query.service.translate.function.QueryFunctionNames` constants holder shared with
  `BuiltInQueryFunctions`. No new packages; the unwrapping itself is a private helper in the same class.
- **Behavior/API**: filters already accepted (HTTP 201 on suite create) stop failing at run creation; a
  previously failing request becomes 202. `nc` keeps its total-over-null semantics (`is not false` wrapper)
  and the multi-turn ALL-turns-match quantifier is unaffected.
- **Data**: none — no schema change, no migration, no stored-filter rewrite (existing stored filters are
  reinterpreted at translation time).
- **Config**: none — no new properties, `docs/configuration.md` unchanged.
- **Docs**: `docs/patterns/test-cases-query-entity.md` mentions "`co`/`nc` on an `ARRAY` field → JSONB
  containment" and needs the wrapper case noted.
- **Risks**: (1) a client that deliberately wanted the previous (broken) `LIKE` behavior on an array field
  gets containment instead — impossible in practice, that SQL never executed; (2) case-insensitive
  containment scans the array elements per row — acceptable at test-case volumes, and it forfeits no index
  the bare form was using: `test_cases.data` has no GIN index, and both forms filter on the extracted
  expression `data -> '<field>'`, which `jsonb_ops` cannot serve.
- **Rollout**: single backend change, no FE coordination, no feature flag, no data backfill.
- **Test plan**: four functional tests in `TestCaseQueryAndFilterFunctionalTests` (executed against real
  Postgres — `/queries/execute` row selection for `co`/`nc`/text-rendering over a fixture that includes
  off-type, numeric-element and absent-value rows, plus run creation returning 202 with the matching count),
  one in `MultiTurnFilterFunctionalTests` pinning the predicate inside the ALL-turns-match quantifier over a
  `perTurn` `ARRAY` field, and translator-level unit coverage in `FilterTranslatorArrayContainmentTest` for
  the wrapped-operand shapes (including the upper-case wrapper name and the rejected null operand) and the
  unchanged bare/scalar shapes.

Status: **Implemented** (all items above; `FilterTranslator` routes wrapped array operands to
case-insensitive whole-element containment, covered by 7 new unit tests and 5 new functional tests plus the
docs sync).
