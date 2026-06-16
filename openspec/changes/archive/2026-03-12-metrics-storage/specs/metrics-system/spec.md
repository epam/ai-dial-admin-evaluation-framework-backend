# Metrics System (Delta)

## MODIFIED Requirements

### Requirement: Store named metric outputs
The system SHALL store metric outputs as named results via the eval summary model. Each metric computation produces one EvalSummary row per test case, containing all metric scores in a structured `metric_values` JSONB column. Individual named outputs are keyed by TSMD name and output name (e.g., `{"Accuracy": {"score": 0.85, "f1": 0.78}}`). Values SHALL be numeric (typically normalized to [0..1]). Optional detailed info SHALL be stored in a separate `metric_infos` JSONB column.
Status: **Planned**

#### Scenario: Fixed named outputs stored in metric_values
- **WHEN** a metric produces results for a test case
- **THEN** outputs SHALL be stored in the `metric_values` JSONB column of the `test_case_eval_summaries` table, keyed by TSMD name with nested output names and numeric values

#### Scenario: Detailed info stored separately
- **WHEN** a metric produces optional info/metadata alongside output values
- **THEN** info SHALL be stored in the `metric_infos` JSONB column, keyed by TSMD name with nested output names and arbitrary JSON detail objects

#### Scenario: Reproducibility via computation_id and metric_declaration_version_id
- **WHEN** a MetricResult is stored
- **THEN** the computation SHALL be tracked via `computation_id` in the eval summary row, and the MetricDeclarationVersion used SHALL be recorded in `run_metric_snapshots` for that computation

## ADDED Requirements

### Requirement: Eval summary as MetricResult storage model
The system SHALL use the `test_case_eval_summaries` table as the storage model for MetricResults, replacing the originally planned separate `metric_results` table from the entity-relationship model. This wide-table design stores all metric outputs for a test case in a single row alongside the test case context, optimized for grid rendering and OLAP-ready denormalization. See `metrics-storage` spec for full schema and API details.
Status: **Planned**

#### Scenario: MetricResult storage location
- **WHEN** the metric computation pipeline produces results
- **THEN** results SHALL be stored in `test_case_eval_summaries` (analytics DB), not in a separate normalized `metric_results` table
