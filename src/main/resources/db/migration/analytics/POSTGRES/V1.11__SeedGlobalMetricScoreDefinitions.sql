-- Seed the predefined DEFAULT metric-score statistics (applied to every suite) and the DEFAULT
-- "overall" composite. Each expression is a self-contained StructuredQuery (entity eval_summaries,
-- aggregate mode) selecting a single aliased "value", scoped by the runtime params runId/computationId.
-- The per-metric statistics target a runtime :metricField param; "overall" averages the run's metrics
-- via mean(:metricAvgs) (the executor binds :metricAvgs to the per-metric avg(...) terms). Deterministic
-- ids + ON CONFLICT DO NOTHING make this seed idempotent across re-applies.

INSERT INTO metric_score_definition (id, type, name, description, expression, target_id)
VALUES (
    '11111111-1111-1111-1111-111111111101', 'DEFAULT', 'AVG', 'Arithmetic mean of a metric across the run''s test cases',
    '{"entity":"eval_summaries","mode":"aggregate","select":[{"expr":{"type":"fn","name":"avg","args":[{"type":"param","name":"metricField"}]},"as":"value"}],"filter":{"op":"and","args":[{"op":"eq","args":[{"type":"field","name":"test_suite_run_id"},{"type":"param","name":"runId"}]},{"op":"eq","args":[{"type":"field","name":"computation_id"},{"type":"param","name":"computationId"}]}]}}'::jsonb,
    NULL)
ON CONFLICT DO NOTHING;

INSERT INTO metric_score_definition (id, type, name, description, expression, target_id)
VALUES (
    '11111111-1111-1111-1111-111111111103', 'DEFAULT', 'P10', '10th percentile of a metric across the run''s test cases',
    '{"entity":"eval_summaries","mode":"aggregate","select":[{"expr":{"type":"fn","name":"percentile_cont","args":[{"type":"value","value_type":"decimal","value":"0.1"},{"type":"param","name":"metricField"}]},"as":"value"}],"filter":{"op":"and","args":[{"op":"eq","args":[{"type":"field","name":"test_suite_run_id"},{"type":"param","name":"runId"}]},{"op":"eq","args":[{"type":"field","name":"computation_id"},{"type":"param","name":"computationId"}]}]}}'::jsonb,
    NULL)
ON CONFLICT DO NOTHING;

INSERT INTO metric_score_definition (id, type, name, description, expression, target_id)
VALUES (
    '11111111-1111-1111-1111-111111111104', 'DEFAULT', 'P90', '90th percentile of a metric across the run''s test cases',
    '{"entity":"eval_summaries","mode":"aggregate","select":[{"expr":{"type":"fn","name":"percentile_cont","args":[{"type":"value","value_type":"decimal","value":"0.9"},{"type":"param","name":"metricField"}]},"as":"value"}],"filter":{"op":"and","args":[{"op":"eq","args":[{"type":"field","name":"test_suite_run_id"},{"type":"param","name":"runId"}]},{"op":"eq","args":[{"type":"field","name":"computation_id"},{"type":"param","name":"computationId"}]}]}}'::jsonb,
    NULL)
ON CONFLICT DO NOTHING;

INSERT INTO metric_score_definition (id, type, name, description, expression, target_id)
VALUES (
    '11111111-1111-1111-1111-111111111105', 'DEFAULT', 'MIN', 'Minimum value of a metric across the run''s test cases',
    '{"entity":"eval_summaries","mode":"aggregate","select":[{"expr":{"type":"fn","name":"min","args":[{"type":"param","name":"metricField"}]},"as":"value"}],"filter":{"op":"and","args":[{"op":"eq","args":[{"type":"field","name":"test_suite_run_id"},{"type":"param","name":"runId"}]},{"op":"eq","args":[{"type":"field","name":"computation_id"},{"type":"param","name":"computationId"}]}]}}'::jsonb,
    NULL)
ON CONFLICT DO NOTHING;

INSERT INTO metric_score_definition (id, type, name, description, expression, target_id)
VALUES (
    '11111111-1111-1111-1111-111111111106', 'DEFAULT', 'MAX', 'Maximum value of a metric across the run''s test cases',
    '{"entity":"eval_summaries","mode":"aggregate","select":[{"expr":{"type":"fn","name":"max","args":[{"type":"param","name":"metricField"}]},"as":"value"}],"filter":{"op":"and","args":[{"op":"eq","args":[{"type":"field","name":"test_suite_run_id"},{"type":"param","name":"runId"}]},{"op":"eq","args":[{"type":"field","name":"computation_id"},{"type":"param","name":"computationId"}]}]}}'::jsonb,
    NULL)
ON CONFLICT DO NOTHING;

-- "overall": unweighted mean of the run's per-metric averages, computed via the DSL. The executor
-- binds :metricAvgs to an array of avg(metric:<tsmd>:<field>) terms for the run's discovered metrics.
INSERT INTO metric_score_definition (id, type, name, description, expression, target_id)
VALUES (
    '11111111-1111-1111-1111-1111111111ff', 'DEFAULT', 'overall', 'Overall run score: unweighted mean of each metric''s average',
    '{"entity":"eval_summaries","mode":"aggregate","select":[{"expr":{"type":"fn","name":"mean","args":[{"type":"param","name":"metricAvgs"}]},"as":"value"}],"filter":{"op":"and","args":[{"op":"eq","args":[{"type":"field","name":"test_suite_run_id"},{"type":"param","name":"runId"}]},{"op":"eq","args":[{"type":"field","name":"computation_id"},{"type":"param","name":"computationId"}]}]}}'::jsonb,
    NULL)
ON CONFLICT DO NOTHING;
