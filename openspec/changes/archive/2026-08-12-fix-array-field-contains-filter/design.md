## Context

See [proposal.md](proposal.md) — Why. Current translation state, as it bears on the approach:

- `FilterTranslator.toComparison` routes `co`/`nc` to `arrayContains(...)` only when
  `isArrayField(leftExpr, bindings)` holds — i.e. the left operand is a bare `FieldExpr` whose binding
  type is `ARRAY`. Everything else reaches `((Field<String>) left).likeIgnoreCase(...)`.
- `TestCaseFieldBindingsBuilder` binds `array`/`object` schema fields to a raw JSONB path (`data -> 'f'`)
  and scalar fields to a text extraction (`data ->> 'f'`). So an array field's `Field` is `Field<JSONB>`.
- The function catalog casts blindly: `lower` is `DSL.lower((Field<String>) arg)`. On a `Field<JSONB>`
  this renders `lower(<jsonb>)`, which is not a Postgres function — the statement fails at execution, so
  no result set is possible on this path today.
- Containment predicates are jOOQ plain-SQL templates with bound operands (`{0} ?? {1}`,
  `{0} @> to_jsonb({1})`), rendered nested inside `QueryDslRunnableTestCaseSelector`'s
  `NOT EXISTS (… WHERE (<pred>) IS NOT TRUE)` all-turns lateral.
- Negated operators are made total over nulls by `nullSatisfies` (`(<pred>) is not false`).

Constraint: the change must stay inside `.experimental.query.service.translate` (layering unchanged, no
repository or service edits) and must not change the wire contract — the stored filter JSON is fixed by
what the Admin UI already sends.

## Goals / Non-Goals

**Goals:**

- One routing decision point: whether a `co`/`nc` left operand denotes an array field, whether or not a
  case-normalizing wrapper sits on top of it.
- Keep the emitted predicate parameterized and index-friendly on the existing bare-field path.
- Keep `nc` total over nulls through the existing `nullSatisfies` wrapper rather than a second mechanism.

**Non-Goals:**

- Operand type-checking in the function catalog; translating run-path DB errors to 4xx; substring
  semantics (all covered in the proposal's "Not in scope").
- Any change to how `lower`/`upper` translate outside the `co`/`nc` array branch.
- Rewriting stored filters — the wrapper is reinterpreted at translation time, every time.

## Decisions

### 1. Read `lower`/`upper` over an array field as a case-insensitivity hint, not as SQL

Resolve the left operand of `co`/`nc` to an "array operand" in two steps: a bare `FieldExpr` bound to
`ARRAY` → case-sensitive; a single-argument `lower`/`upper` `FnExpr` whose argument is such a
`FieldExpr` → case-insensitive. Anything else is not an array operand and falls through to `LIKE`.

Rationale: `lower(jsonb)`/`upper(jsonb)` have no SQL meaning, so no client can be relying on their
literal translation — the only intent expressible by that shape is "compare ignoring case". Reading it
that way fixes the reported filter without a wire change or an FE release.

Alternatives considered: (a) reject the shape with HTTP 400 at suite write time — spec-faithful
(`lower(text) → text`) but leaves the user blocked pending an FE change; (b) silently drop the wrapper
and keep case-sensitive containment — would make `Contains "tee"` miss `"Tee"`, i.e. answer the wrong
question; (c) coerce the JSONB operand to text and keep `LIKE` — matches the serialized array
(`["Tee","x"]`), so `co "e\",\"x"` would match; nonsense semantics.

### 2. Case-insensitive containment via `jsonb_array_elements_text` + `EXISTS`, type-guarded inside the function argument

Emit, as a plain-SQL template with the operand bound:

```
exists (
  select 1 from jsonb_array_elements_text(
    case when jsonb_typeof({col}) = 'array' then {col} else '[]'::jsonb end
  ) as e(v) where lower(e.v) = lower({operand})
)
```

Rationale: whole-element comparison with case folding, no operand text ever concatenated into SQL. The
type guard is required, not cosmetic: `jsonb_array_elements_text` **raises** on a scalar or object value
(verified — `ERROR: cannot extract elements from a scalar` for an `array`-declared field holding
`"tee"`, which a coerced import can produce), which would turn one off-type row into a failed statement —
the same class of failure this change fixes.

The guard sits **inside the function argument** rather than as a sibling `AND` conjunct precisely because
`AND` operand evaluation order is not guaranteed: top-level `WHERE` conjuncts are cost-ordered by the
planner, so `jsonb_typeof(col) = 'array' AND EXISTS (…)` would rely on an ordering Postgres does not
promise (on the run-creation path the predicate is always nested inside the all-turns
`NOT EXISTS (… WHERE (<pred>) IS NOT TRUE)`, which cannot be split into separate quals, but the
`/queries/execute` path does emit it as a top-level conjunct). `CASE` evaluates only the selected branch,
so the guard holds under any plan. Both forms were executed against Postgres 17.5 and return identical
rows.

Null polarity is preserved: for a null/absent column the guard selects `'[]'::jsonb`, the `EXISTS` is
false, so `co` does not match and `nc` — via `nullSatisfies`'s `(<pred>) is not false` — does, matching
the existing null-handling requirement. (Verified: `nc` matches the null, absent, and off-type rows.)

Alternatives considered: (a) `jsonb_path_exists` with `like_regex … flag "i"` — the pattern must be a
jsonpath literal (`vars` cannot supply it), forcing operand concatenation into SQL; rejected outright.
(b) Lower-casing the array into a new JSONB value and reusing the `?` operator — same subquery cost plus
an extra aggregation. (c) `col ?| array[...]` over enumerated casings — combinatorial, wrong.

### 2a. Elements are compared by their JSON text rendering, which diverges from the bare `?` path

`jsonb_array_elements_text` renders every element as text, so a **string** operand can match a
non-string element: `co(lower(tags), "1")` matches `tags = [1, 2]`, whereas the bare-field
`tags ? '1'` does not (the `?` operator only inspects string elements). Accepted rather than papered
over — restricting the expansion to string elements would make a wrapped filter miss values the user can
see in the grid, which is the more surprising outcome. The divergence is pinned by a spec scenario so it
is a decision on record, not an accident.

### 3. Keep the resolution in `FilterTranslator` as a private helper

The unwrapping returns a small private record (the resolved `FieldExpr` plus an `ignoreCase` flag) or
`null`. It stays next to `isArrayField`/`arrayContains` in `FilterTranslator` rather than becoming an
injectable component.

This is a deliberate exception to the project rule *"Specialized components (Parser, Validator,
Converter) MUST be top-level injectable classes, NOT private/inner methods"* (`openspec/config.yaml`
context): the helper performs **syntax routing over the filter tree** — no parsing of external input, no
validation verdict, no collaborators — is used at exactly one call site, and is covered directly through
`FilterTranslator` by `FilterTranslatorArrayContainmentTest`. It sits at the same scope as the existing
private `isArrayField`/`arrayContains`/`containsPattern` helpers in that class; promoting it to a bean
would add indirection without adding a testing or reuse seam.

### 4. Non-string right operands keep the case-sensitive `@>` form

Case folding is meaningless for numbers/booleans, so a wrapped operand with a non-string literal simply
drops the wrapper and uses the existing `@>` branch. This keeps one code path per element type rather
than duplicating the string/non-string split per wrapper state.

### Component interaction flow (unchanged except the marked step)

`TestSuiteRunService.createRun` → `RunnableTestCaseCounter` → `QueryDslRunnableTestCaseSelector.compile`
→ `TestCaseFieldBindingsBuilder.buildScoped` + **`FilterTranslator.toComparison` (changed routing)** →
`PostgresTestCaseRepository.countValidByDatasetIdExcludingIdsMatching`. Same for the snapshot phase and
for `/queries/execute` via `StructuredQueryBuilder`. No transaction-boundary, datasource, or DTO change;
no data model change.

## Risks / Trade-offs

- **Off-type row would break the statement** (array-declared field holding a scalar or object) → the
  type guard lives inside the `jsonb_array_elements_text` argument (decision 2), where `CASE` guarantees
  only the selected branch is evaluated, so such a row yields the empty array and simply does not match
  under any plan. A test must seed an off-type row, or a later refactor can drop the guard with the suite
  still green.
- **Text-rendering divergence from the bare `?` path** (decision 2a) → documented in the spec; a wrapped
  `co "1"` matches `[1, 2]` while the bare form does not.
- **Off-type divergence from the bare `?` path** → for a row whose array-declared field holds a non-array
  value, the guard makes the wrapped form not match, whereas `?` matches a string value equal to the
  operand and an object value carrying it as a key (`@>` matches an equal scalar). Verified in Postgres
  17.5. Accepted: the alternative — reproducing `?`'s scalar/object semantics inside the expansion — would
  make "contains an element" mean something different per operand shape for no user-visible gain. Pinned by
  a spec scenario and by the `tc-offtype` fixture row.
- **Wrapper name recognized case-insensitively** → `QueryFunctionRegistry` lowercases names before
  dispatch, so `LOWER` is a *valid* function name. Matching the wrapper case-sensitively would leave that
  spelling routed into the literal `lower(jsonb)` translation — the exact GH #142 failure, just spelled
  differently. The two spellings live in `QueryFunctionNames`, shared with the catalog registration, so
  routing and registration cannot drift.
- **Per-row element scan on the wrapped path** → confined to the wrapped operand. It forfeits no index
  access: `test_cases.data` carries no GIN index, and both forms filter on the extracted expression
  `data -> '<field>'`, which `jsonb_ops` cannot serve (verified with `enable_seqscan=off`). Test-case
  counts per dataset are small, and the same shape already runs inside the multi-turn lateral.
- **`EXISTS` subquery nested inside the all-turns lateral** → structurally identical to the existing
  nested plain-SQL condition; the new template contains no `?` character, so it avoids the jOOQ
  bind-placeholder escaping hazard that the `{0} ?? {1}` template needs.
- **Whole-element ≠ the UI's "Contains" wording** (`"tee-shirt"` will not match `"tee"`) → deliberate,
  chosen to keep one meaning for array `co` across wrapped and bare operands; recorded in the spec
  scenarios so a future substring variant must be an explicit decision.
- **`upper` treated identically to `lower`** → both are case-normalizations; accepting only `lower`
  would leave an equivalent shape broken for no reason.

## Migration Plan

No schema, data, or config migration. Single backend deployment; stored `testCaseFilter` values are
untouched and reinterpreted at translation time. Rollback is a plain revert — filters return to failing
at run creation, with no data to undo.
