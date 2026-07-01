## Why

The multi-step conversation POC drives each turn from `multistepInputBindings` — a
suite-level `List<List<InputBindingDto>>`, one binding set per turn. That fixes the turn
count in the suite config, but the number of turns is really a **per-test-case** property:
different test cases in the same suite legitimately need different numbers of turns.
Suite-level per-turn bindings cannot express that, and authoring them (repeating the whole
binding structure per turn) is awkward.

## What Changes

- **BREAKING** Remove suite-level `multistepInputBindings` entirely: DTO fields
  (`TestSuiteRequestDto`, `TestSuiteResponseDto`), the `SuiteSnapshotDto` field, the
  `EvaluationContext` field, the `JsonbMapper` mapping methods, and the
  `test_suites.multistep_input_bindings` DB column (via a new Flyway migration + jOOQ regen).
- Keep the suite-level `multiStep` boolean flag (the only retained multi-turn suite config).
  Its meaning changes: a multi-step suite now uses its **regular single `inputBindings`**,
  exactly like a single-step suite.
- Turn execution becomes **data-driven, per test case**: when a bound column's value is a
  JSON **array**, the engine iterates it — turn `i` resolves the template with element `i` of
  each array-valued bound column. Scalar columns and `constantValue` bindings broadcast on
  every turn. Turn count `N` = the common length of the array-valued bound columns in that
  test case's data, so two test cases in the same suite can run different numbers of turns.
- Per-test-case failure isolation: mismatched array lengths, `N` over the cap
  (`MAX_CONVERSATION_STEPS`), or no array-valued bound column → that test case's result is
  `ERROR` with a clear message; other test cases in the run proceed.
- Multi-step config validation reduces to the **normal** binding validation against the single
  `inputBindings` plus the existing "request body must be JSON with a top-level `messages`
  array" check. The per-step / non-empty / step-cap validation is removed.
- Unchanged: single `TestCaseRunResult` per case; `extractedColumns` = JSON **array** of
  per-step maps; `responseBody` = the last turn's raw response body; `ExtractedColumnsNormalizer`
  at the metric boundary; full-history resend; hardcoded `choices[0].message.content` extraction;
  non-streaming; fail-fast; `SuiteSnapshotDto` version stays `"2"`.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `multi-step-conversation`: the binding source changes from suite-level per-turn
  `multistepInputBindings` to the suite's single `inputBindings`; turn count is derived
  per test case from array-valued bound columns rather than from suite config; config
  validation and failure semantics change accordingly.

## Impact

- **DB**: drop `test_suites.multistep_input_bindings` (Flyway `V1.24`), regenerate jOOQ
  (`TestSuites`, `TestSuitesRecord`).
- **API**: `POST`/`PUT`/`GET`/clone `/api/v1/test-suites` drop the `multistepInputBindings`
  field. `multiStep` remains. Multi-turn suites now require array-valued dataset columns bound
  by the single `inputBindings`.
- **Execution**: `MultiStepConversationExecutor` internals rewritten (turn derivation +
  per-turn data projection reusing `ResolvedRequestService.resolve`); `EvaluationWorker`
  delegation unchanged.
- **Validation**: `SuiteValidationService.validateMultiStep` reduced; reuses `BindingValidator`.
- **Docs/specs**: `docs/database-schema.md`, `AGENTS.md` inline convention,
  `openspec/specs/multi-step-conversation/spec.md`, `openspec/specs/README.md`.
- No config properties added; `MAX_CONVERSATION_STEPS` stays a non-configurable constant.
