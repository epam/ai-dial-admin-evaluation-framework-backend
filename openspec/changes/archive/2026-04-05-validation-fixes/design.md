## Context

Three independent bugs produce incorrect outcomes in two subsystems.

**FILE validation false positive / silent gap** — `TestCaseValidationService.validateFileFields` guards against `null` but not against blank strings. `CsvCellParser.parseCell()` is designed to never return null (blank cells become `""`), so CSV-imported empty FILE cells always reach `FileRefValidator.validate("", ...)` and trigger "File reference must not be blank" even for optional, unbound fields. The symmetric gap: the required-field check at line ~125 also only checks `null`, so a required FILE field with `""` silently passes with no warning at all.

**Metric evaluation interruption** — `InProcessMetricEvaluationExecutor.evaluateAndBuild()` reuses `cancellation-grace-period-ms` (30 s) as a **per-result** wait for all TSMD futures on a single result. When a metric provider is slow (LLM-backed evaluations routinely exceed 30 s), this timeout fires, cancels the futures with `mayInterruptIfRunning=true`, and the thread interrupt closes the underlying socket mid-response, producing `SocketException: Closed by interrupt`. This is a naming/semantics mismatch: "cancellation grace period" implies a shutdown-drain wait, but the property is acting as a per-result request timeout. Phase 1 (`InProcessEvaluationExecutor`) correctly separates these concerns into `requestTimeoutMs` (per test case, user-configurable) and `cancellationGracePeriodMs` (total phase drain). Phase 2 has no equivalent `requestTimeoutMs` — it only has the misused `cancellationGracePeriodMs`.

## Goals / Non-Goals

**Goals:**
- Eliminate the false-positive "File reference must not be blank" warning for optional FILE fields with empty string values.
- Ensure required FILE fields with empty string values produce the correct "field is missing" warning — both via the schema required-field check and via the data-vs-binding check.
- Introduce `metric-evaluation.per-result-timeout-ms` as the dedicated per-result wait for TSMD futures, replacing the misused `cancellationGracePeriodMs` for that purpose.
- Remove `cancellation-grace-period-ms` from `metric-evaluation.*` config (it was only ever used as the per-result timeout; a phase-level drain does not exist in Phase 2).

**Non-Goals:**
- Changing `CsvCellParser` to return null for empty cells — too many downstream callers depend on the no-null contract.
- Adding a phase-level cancellation drain to Phase 2 (metric evaluation is append-only; hard shutdown on cancel is acceptable).
- Adding retry logic specific to the interrupt scenario.
- Extending the blank-as-absent semantics to STRING or other non-FILE types — `""` is a legitimate value for STRING fields; extending it would change the semantics of `required` from "key must be present" to "key must be non-blank" without spec-level backing.

## Decisions

### D1: Treat blank string as "not provided" in FILE validation, not as an invalid reference

Blank string (`""`) and `null` are semantically equivalent for FILE fields: the user did not supply a file. `validateFileFields` is already annotated with a comment saying null is OK because the required-field check handles it. Extending that logic to blank is the minimal, non-disruptive fix.

_Alternative considered_: Return null from `CsvCellParser` for empty cells. Rejected — the parser is used across many paths and its "never-null" contract prevents NPEs in downstream code.

### D2: Scope the blank-string guard to FILE type only across all fix sites

Extending the null-check to `(value == null || isBlank(value))` for ALL schema types would change semantics for STRING fields (where `""` is a legitimate non-empty value). The fix must be narrowly scoped to `SchemaFieldType.FILE` to avoid unintended behavior changes for other types. This applies to all three fix sites: `validateFileFields`, the schema required-field check, and the data-vs-binding check.

### D3: Fix the data-vs-binding blank-string gap in the same change

The data-vs-binding check (lines 110–120 of `TestCaseValidationService`) tests `value == null` to decide whether a required template variable's data field is missing. It does not know the field type. After D1 fixes `validateFileFields` to skip blank strings (treating them as "not provided"), a required-via-binding FILE field with `""` would become **completely silent** — Phase A emits no warning (only checks null), and Phase C now also emits no warning (blank is skipped). This is a regression versus the current behavior where Phase C at least emits a TYPE warning.

The fix: build a `Map<String, SchemaFieldType>` from the schema before the Phase A loop and extend the null condition for FILE-typed fields only — `value == null || (fieldType == FILE && value instanceof String s && s.isBlank())`. This requires a schema type lookup by field name but is otherwise a contained, 4-line change.

Keeping this fix in a follow-up would leave the data-vs-binding path in a worse state than before D1, so it is included here.

### D4: Replace `cancellationGracePeriodMs` in Phase 2 with a dedicated `perResultTimeoutMs`

Phase 1 has two distinct timeout concepts:
- `requestTimeoutMs` — per test case, controls how long to wait for the HTTP/SSE response
- `cancellationGracePeriodMs` — total phase drain: waits for all in-flight futures after the dispatch loop

Phase 2 (`MetricEvaluationExecutor`) currently reuses `cancellationGracePeriodMs` as a per-result timeout in `evaluateAndBuild()`. This is wrong on two levels: the name implies shutdown-drain semantics, and the 30 s value is far too low for LLM-backed metric providers (HTTP read timeout is 150 s).

The fix: introduce `metric-evaluation.per-result-timeout-ms` (analogous to `requestTimeoutMs` in Phase 1). Its natural default is 150 000 ms — aligned with the metric provider HTTP read timeout so the HTTP layer can handle stuck calls cleanly rather than having the future timeout interrupt an otherwise-succeeding request. Phase 2 has no equivalent phase-level drain (append-only writes; hard shutdown on cancel is safe), so `cancellation-grace-period-ms` is removed from `metric-evaluation.*` entirely.

_Alternative considered_: Keep `cancellation-grace-period-ms` and just raise its value to 120 s. Rejected — it preserves the naming confusion and makes future engineers wonder why the "grace period" is 120 s. The rename has zero runtime risk (internal only, no API change) and pays the naming debt now.

The property is exposed via `METRIC_EVAL_PER_RESULT_TIMEOUT_MS`; default defined in `application.yml`.

## Risks / Trade-offs

- **`cancellation-grace-period-ms` removal is a breaking config change**: Any deployment that explicitly sets `metric-evaluation.cancellation-grace-period-ms` in YAML or env vars will get an unknown-property warning (Spring Boot logs it but does not fail by default). Mitigation: document the rename in `docs/configuration.md`; the old property simply stops having any effect if left in place.
- **Per-result timeout aligned with HTTP read timeout**: With `per-result-timeout-ms: 150 000`, a genuinely stuck provider call will hold a result's TSMD futures for up to 150 s before being cancelled. This is intentional — it lets the HTTP layer decide, not the future timeout layer. The semaphore (`default-concurrency-per-provider`) bounds the number of simultaneous stuck calls.
- **Blank FILE required-field fix changes existing behaviour**: A required FILE field with `""` will now produce a warning where it previously passed silently. This is a bug fix, not a regression, but any existing test data or test suites that rely on the silent pass will surface as invalid after revalidation.

## Migration Plan

No database migrations required. No API shape changes. After deployment:
1. Existing test suites and test cases are unaffected until revalidation is triggered.
2. Any `metric-evaluation.cancellation-grace-period-ms` value in existing deployment YAML becomes a no-op (Spring Boot logs unknown property, does not fail). Operators should switch to `METRIC_EVAL_PER_RESULT_TIMEOUT_MS` / `metric-evaluation.per-result-timeout-ms`.
3. The new default (150 s) is more permissive than the old (30 s) — no evaluation runs will be made more restrictive by this change.
