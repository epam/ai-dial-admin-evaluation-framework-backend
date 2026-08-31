## MODIFIED Requirements

### Requirement: Evaluation executor interface
The system SHALL define an `EvaluationExecutor` interface with a single `execute(EvaluationContext)` method. The `EvaluationContext` SHALL carry: `runId`, `testSuiteId`, execution settings (concurrency, timeout, retry, rate limit), a cancellation signal, a progress callback, a result sink, and a nullable `InlineMetricEvaluator` (non-null only when `TestSuiteEvaluationJob` has determined the run is inline-mode; see `metric-evaluation`). This interface enables swapping in-process execution with K8s Job submission without changing orchestration code.

Status: **Planned**

> **Note — spec collision**: `openspec/changes/ef-as-dial-app/specs/eval-execution-engine/spec.md` also rewrites this same requirement's carry-list sentence, to remove the JWT token field (DIAL App mode auth work). This delta is written against the currently archived `eval-execution-engine` spec text and does not attempt to merge with that pending delta. Whichever of the two changes archives second SHALL rebase this paragraph by hand to include both edits (the `InlineMetricEvaluator` addition and the JWT token field removal).

#### Scenario: In-process executor is the default
- **WHEN** the application starts with default configuration
- **THEN** the `InProcessEvaluationExecutor` bean SHALL be the active `EvaluationExecutor` implementation

#### Scenario: Executor receives fully populated context
- **WHEN** `TestSuiteEvaluationJob` dispatches a run
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) and pass it to the executor. Context construction and cancellation signal registration SHALL occur before async dispatch to prevent race conditions.

#### Scenario: Non-inline run carries a null InlineMetricEvaluator
- **WHEN** `TestSuiteEvaluationJob` determines a run is not inline-mode
- **THEN** the `EvaluationContext` it constructs SHALL carry `inlineMetricEvaluator = null`, and `TurnLoopExecutor`'s execution path SHALL be byte-for-byte identical to its behavior before this change

#### Scenario: Inline run carries a non-null InlineMetricEvaluator
- **WHEN** `TestSuiteEvaluationJob` determines a run is inline-mode
- **THEN** the `EvaluationContext` it constructs SHALL carry a non-null `InlineMetricEvaluator` supplied by the EF backend's per-run factory

#### Scenario: An evaluator that throws does not replace the SUCCESS row
- **WHEN** the `InlineMetricEvaluator` supplied on the context throws or otherwise fails to honor the "must not throw" SPI contract (see `evaluation-runner-core-module`) while `TurnLoopExecutor` evaluates a turn's row inline
- **THEN** that failure SHALL NOT cause the turn's real SUCCESS row to be replaced by a synthetic `REQUEST_RESOLUTION_ERROR` row — the seam is designed so `TurnLoopExecutor`'s existing exception handling, which synthesizes such a row for genuine request-resolution failures, is never reached by an inline-evaluation defect
