## 1. Preserve dispatch context and add run orchestration

- [x] 1.1 In backend `TestSuiteEvaluationJob.dispatch`, explicitly capture/wrap native OTel context independently of Micrometer decoration; add regression tests using the real configured executor in virtual and platform modes (done: targeted tests pass and assert the original span context/baggage reaches the job, including exception-scope restoration).
- [x] 1.2 In `TestSuiteEvaluationJob` and shared `TracingConstants`, add `eval.run` orchestration with run/suite attributes and unconditional span cleanup without altering run-status or transaction behavior (done: targeted job tests pass for direct parenting, missing incoming span, success, failure, cancellation, and rejection).
- [x] 1.3 In `CorrelationIdInterceptor`, the DIAL trigger path, and job dispatch, add scoped correlation-ID capture/restore, explicit trigger-header forwarding, and cleanup at synchronous/async HTTP handoff; keep OTel context independent (done: targeted interceptor/trigger/job tests pass for valid/generated IDs, forwarded headers, credential preservation, and no MDC leakage).
- [x] 1.4 Add and run a tracing-enabled Postgres functional test in `functional/tests` for public direct dispatch using an in-memory exporter (done: the application context boots and exported orchestration stays in the incoming trace; run status/results and existing auth behavior remain correct).

## 2. Own linked test-case roots in runtime memory

- [x] 2.1 Add DB-free run-scoped `EvaluationTraceSession` in shared `runner.job` and root-span constants in `runner.util.TracingConstants`; implement lazy per-case/repetition roots, orchestration links, idempotent close, and race-safe late access (done: new session unit tests execute and pass, asserting distinct trace IDs, invalid parent IDs, exact link contexts, run isolation, disabled tracing, and close/create races).
- [x] 2.2 Pass the session through backend `EvaluationContext` construction and make the run owner close it after executor shutdown on every exit (done: targeted job/session tests pass for successful, failed, cancelled, and rejected ownership paths with exactly-once root completion and released state).
- [x] 2.3 In shared `EvaluationWorker`, parent the existing execution span to the new lifetime root while preserving request/turn/retry/MCP subtrees, baggage, and persisted result trace IDs (done: new/updated worker and request-chain tests execute and pass for multiple cases/repetitions and multi-request/multi-turn results; existing execution spans keep Phase 1 durations).
- [x] 2.4 Adapt `eval-cli` evaluation-context/session ownership to close resources even when setup/output/execution fails (done: `:eval-cli:test` and affected `:evaluation-runner-core:test` suites pass with unchanged CLI/CSV contracts and no retained roots).

## 3. Parent Phase 2 metrics to their test-case roots

- [x] 3.1 Pass the same runtime session through backend `MetricEvaluationContext` and select an explicit case/repetition parent in `MetricEvaluationWorker`; preserve existing metric/provider subtrees and fresh metric baggage (done: targeted metric-worker/executor tests pass and exported metric parent IDs equal their case-root IDs, not orchestration or execution-child IDs).
- [x] 3.2 Support lazy metric-only roots for imported results without reconstructing historical span contexts or rewriting stored execution trace IDs (done: targeted import and metric-only tests execute and pass, asserting new linked metric traces and unchanged historical `traceId`).
- [x] 3.3 Extend metric tests for multiple requests/turns/repetitions, concurrent runs, skipped conditions, timeout/cancellation races, and no-op tracing (done: tests execute and pass with deterministic parent/link assertions, no phantom metric spans, and no root resurrection after close).
- [x] 3.4 Run a tracing-enabled Postgres functional suite covering Phase 1 followed by real Phase 2 wiring with an in-memory exporter (done: application boots, roots cover execution/metric intervals, metrics and execution share case traces, provider headers/baggage use metric context, and REST/persistence still expose only existing execution `traceId` fields).

## 4. Verify Core-routed continuity and compatibility

- [x] 4.1 Add a booted functional test emulating DIAL Core route forwarding into `EvalExecuteInternalController`, including W3C and custom correlation headers (done: executed test proves public start/trigger/internal request/orchestration continuity, distinct linked case traces, correctly parented metrics, preserved API-key/PRK handling, and emitter-before-dispatch ordering).
- [x] 4.2 Extend routed functional coverage for trigger failure, duplicate dispatch, disconnect cancellation, and tracing disabled (done: executed tests preserve expected HTTP/run outcomes and verify tracing cleanup without changing cancellation or SSE behavior).
- [x] 4.3 Run shared runner and CLI regression suites plus relevant backend analytics/import tests (done: executed suites pass and no database schema, REST DTO, CSV column, sampling-policy, or deployment-property changes are introduced).
- [x] 4.4 Run `./gradlew spotlessApply`, `./gradlew spotlessCheck`, relevant module/backend Checkstyle main/test tasks, architecture checks, and the final build (done: formatting, Checkstyle, module/layer constraints, and `./gradlew clean build` all pass; do not check off on static review alone).

## 5. Document and reconcile the tracing contract

- [x] 5.1 Add `docs/patterns/evaluation-tracing.md`, link it from `docs/patterns/README.md`, update `docs/key-packages.md` and relevant DIAL App documentation, and describe Grafana span-link direction, run-scoped TraceQL, root export timing, sampling/retention limits, and import behavior (done: documentation matches verified span shape and existing code paths).
- [x] 5.2 Update AGENTS.md per AGENTS.md Maintenance guidelines for the run-owned cross-phase tracing pattern (done: one concise Unique Patterns entry points to the detailed pattern; no session-specific implementation details are added).
- [x] 5.3 Update openspec/config.yaml per Config Maintenance Policy for explicit native OTel boundary capture and transient cross-phase tracing ownership (done: relevant context conventions accurately describe the new pattern without copying feature requirements).
- [x] 5.4 After executable verification, merge this delta into `openspec/specs/observability-and-logging/spec.md` using the sync workflow, mark verified requirements Implemented, correct stale implementation references, and update `openspec/specs/README.md` per Spec Index Maintenance Policy if its summary is materially inaccurate (done: strict validation passes, existing unrelated requirements/scenarios remain intact, and the index describes the verified tracing contract).

## 6. Review regressions

- [x] 6.1 Derive the snapshot-failure trace outcome from the guarded transition in `TestSuiteEvaluationJob`, resolving persisted terminal status when the write affects zero rows (done: regression tests for CANCELLED/COMPLETED/FAILED zero-row outcomes, existing successful failure-write coverage, the tracing functional suite, and formatting/Checkstyle checks pass).
