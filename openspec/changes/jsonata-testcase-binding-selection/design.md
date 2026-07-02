## Context

`multistep-per-turn-column-selection` added `jsonataExpression` to `ResponseBindingSourceDto` and a
reusable `BindingResolver.applyJsonataSelector(columnValue, expression)` helper (serialize the value
to JSON, evaluate via `JsonataEvaluationService`, return the result — `null` on no-match). The
`TestCase` branch of `BindingResolver.resolveSource` still returns the raw `testCaseData.get(columnName)`
with no selector. `MetricDefinitionValidationService` syntax-checks `jsonataExpression` only for
`Response` bindings, emitting the `INVALID_EXPRESSION` warning.

This change extends the identical selector + validation to `TestCase` bindings. It is deliberately
small and additive; the mechanism is already built.

## Goals / Non-Goals

**Goals:**
- Let a `TestCase` binding select a turn/element of a data column via `jsonataExpression`, with the
  same semantics as `Response`.
- Reuse the existing helper and validation code paths — no new components.

**Non-Goals:**
- Putting `jsonataExpression` on the shared base `MetricBindingSourceDto` / on `Constant` (explicit
  scope decision: `TestCase` and `Response` only).
- Changing test-case data storage (arrays are already literal in the authored data).
- Any DB, config, or dependency change.

## Decisions

**1. Field on `TestCaseBindingSourceDto`, not the base.** Mirror `ResponseBindingSourceDto`: add
`@Size(max = 2000) String jsonataExpression` with an OpenAPI `@Schema`. Keeps `Constant` free of the
field. *Alternative (hoist to base):* rejected by scope decision.

**2. Reuse `applyJsonataSelector` in the `TestCase` branch.** After the existing missing-column guard
and `testCaseData.get(columnName)`, pass the value through `applyJsonataSelector(value,
testCaseSource.getJsonataExpression())`. Identical semantics to `Response`: absent expression → raw
value; expression matching nothing → `null`; syntactically-invalid expression cannot reach runtime
because such TSMDs are filtered as invalid at load time (defense-in-depth: the underlying
`evaluate(...)` would throw, failing that metric result).

**3. Validate `TestCase` `jsonataExpression` syntax alongside `Response`.** In
`MetricDefinitionValidationService.validateBindings`, the `TestCase` branch (Check 3) additionally
runs `validateExpression(...)` when `jsonataExpression` is non-blank and emits the existing
`INVALID_EXPRESSION` warning with the correct `$.configBindings`/`$.inputBindings` path. No new
warning code.

**4. Persistence is transparent.** Bindings persist as JSON via polymorphic Jackson; the new field
round-trips with no mapper change (same as the Response field).

## Risks / Trade-offs

- **Slight duplication** of the `jsonataExpression` field across `ResponseBindingSourceDto` and
  `TestCaseBindingSourceDto` → Accepted by the scope decision (keeps `Constant` clean); the resolver
  and validation logic are shared via the existing helper / a shared branch pattern.
- **Ordering vs the sibling change** → This change builds on `multistep-per-turn-column-selection`;
  archive that first so its delta (Response `jsonataExpression`, raw pass-through) is in the main
  specs before this change's deltas extend it to `TestCase`.

## Migration Plan

Code-only, additive, no DB change. Deploy after `multistep-per-turn-column-selection`. Rollback =
revert.

## Open Questions

_(none.)_
