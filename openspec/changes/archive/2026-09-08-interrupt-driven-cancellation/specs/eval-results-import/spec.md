## MODIFIED Requirements

### Requirement: Metric evaluation and score computation run on imported results
Once an import request's results are persisted, the system SHALL asynchronously run metric evaluation and score computation against them (dispatching the same evaluation job used for live runs with the deployment phase skipped), reusing the same `MetricEvaluationExecutor` and `MetricScoreComputation` logic used for live runs, and SHALL transition the run's status through the same lifecycle (`PENDING` → `RUNNING` → `COMPLETED`/`FAILED`, or `RUNNING` → `CANCELLING` → `CANCELLED` when cancelled) as a normal run.

Status: **Implemented**

#### Scenario: Metric evaluation and score computation complete successfully
- **WHEN** an import run's results have been persisted and evaluation is triggered
- **THEN** the system computes metric values and eval summaries for each imported result and produces score-statistic results for the run, and the run transitions to `COMPLETED`

#### Scenario: Score computation failure does not fail an otherwise-successful run
- **WHEN** metric evaluation for an imported run succeeds but score computation fails
- **THEN** the run still transitions to `COMPLETED`, consistent with score computation being treated as a non-fatal, regenerable step for normal runs

#### Scenario: An imported run can be cancelled like a normal run
- **WHEN** a user cancels an import run while it is `PENDING` or `RUNNING`
- **THEN** the system cancels it using the same interrupt-driven mechanism and the same `CANCELLING` → `CANCELLED` transition used for normal runs, with no special-casing based on how the run was created
