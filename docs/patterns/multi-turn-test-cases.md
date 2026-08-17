# Multi-turn — array-based, shared + per-turn data

A multi-turn case is **emergent from data, not a suite flag**: a single `test_cases` row carrying an ordered `multi_turn_data JSONB` array (discriminator `multi_turn_data != null`). `data` (shared/test-case-level fields) and `multi_turn_data` (per-turn fields) **coexist** — no mutual exclusivity, no DB CHECK — and are **independently PATCH-able** (`multiTurnData:null` reverts to single-turn).

## Field scope

Field **scope** is declared per field via `FieldDefinitionDto.perTurn` (**nullable** `Boolean`, absent/false ⇒ shared, so pre-existing schemas and all single-turn cases are unchanged).

Any CSV-derived schema — validation-time, persisted, final/fixup, and preview's auto-detected schema alike — resolves each field's scope in precedence order: declared fields (present by name in the dataset's current schema, including declared-shared) keep their scope verbatim; undeclared columns become `perTurn: true` when the CSV contains at least one multi-turn case (file-level gate — a contiguous same-`testCaseName` run where some row's `turnIndex` parses to an integer), else stay shared (`perTurn` absent).

`CsvSchemaFieldBuilder` (`service.domain.csv`) is the single owner of that construction, and the carry-forward is deliberately `perTurn`-only — `required`/`displayName`/`description` are still dropped by a CSV round trip.

`TestCaseFieldScopeResolver` (`service.domain`) is the single source splitting a schema/map into shared vs per-turn; `TestCaseDataScopeResolver` (`service.domain`) owns the placement rule itself, in a throwing form (`requireCorrectScope`) and a warning form (`inspect`, returning warnings plus the buckets stripped of misplaced keys): a misplaced **declared** field on a write carrying `multiTurnData` → **400** (single-turn writes are never scope-checked); an **undeclared** key has no scope to violate and is never a 400, staying an unknown-field warning; on recomputation paths (dataset revalidation after a schema scope flip, CSV import fixup), where nothing can throw, the same misplacement is reported as an `INVALID_SCOPE` validation warning that also suppresses the collateral unknown-field/required-missing warnings for that field; over-cap (`test-case.multi-turn.max-turns`, default 10) → warning.

A case carrying a non-empty `multiTurnData` while the dataset schema declares **no** `perTurn: true` field → invalidating case-level warning (code `ADDITIONAL`, path `$.multiTurnData`, no `fieldName`/`turnIndex`). Nothing can be stored in those turns and the turn loop collapses the case to `N = 1`, so the turns are dead weight. The trigger is schema-only — never suite bindings, since validity is dataset-scoped — and it is recomputed on every validation pass, so declaring a per-turn column or clearing `multiTurnData` clears it. The warning is **prepended** to the warning list so `validation.max-warnings-per-case` truncation cannot drop it behind a pile of per-field `INVALID_SCOPE` warnings; both coexist when both apply.

## Execution

`EvaluationWorker` dispatches **every** DEPLOYMENT HTTP case — single-turn and multi-turn alike — through the unified `runner.job.TurnLoopExecutor` (the old chat-completions-only `MultiTurnExecutor` was retired).

Turn count `N` is `multiTurnData.length` **iff** `runner.job.PerTurnBindingDetector` finds at least one bound input field with `perTurn: true`; otherwise `N = 1`, built from the case's shared `data` only (a multi-turn case with no per-turn binding is never resent per turn).

Each turn's request body is JSONata-evaluated (`runner.service.RequestBodyEvaluator`) with a `Frame` binding the **previous** turn's reconciled extracted response columns by name (e.g. a `history` response column is reachable as `$history`) — there is no hardcoded `messages` array or `choices[0].message` reply path; history accumulation is entirely the author's JSONata expression (typically `$append($history, [...])`) over whatever response columns the suite extracts.

Streaming is supported on every turn (`DeploymentTurnInvoker` branches on the response's SSE content type; `stream:false` is no longer force-injected), with DIAL `delta.custom_content` chunks merged by `runner.job.CustomContentAccumulator` into the assembled `choices[0].message.custom_content`.

`TurnLoopExecutor`, `PerTurnBindingDetector`, and `CustomContentAccumulator` live in the [`evaluation-runner-core` module](evaluation-runner-core-module.md) alongside the rest of the Phase 1 execution path — they are not part of the EF backend's own `service.domain`/`service.domain.job` packages.

## Persisted result rows

The persisted `testCaseData` is the **merged effective view** `merge(shared, turn[i])` (per-turn wins) for the `N > 1` path — so conditional-metric/metric input see shared fields with **no `ConditionExpressionEvaluator` change** — and the case's own `testCaseData` verbatim for the `N = 1` path (byte-identical to a genuine single-turn row: `turnIndex`/`totalTurns` stay at their non-nullable `0`/`1` builder/DB defaults).

Fail-fast on the first failing turn.

**Guard:** an `MCP_TOOL` suite bound to a dataset with any multi-turn case is rejected at run creation (409).

See [multi-turn-test-case spec](../../openspec/specs/multi-turn-test-case/spec.md).
