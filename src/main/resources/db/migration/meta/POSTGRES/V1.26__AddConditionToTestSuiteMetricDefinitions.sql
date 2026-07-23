-- Conditional metric execution: an optional JSONata expression that gates, per result
-- row (per turn), whether this metric runs. NULL/blank means the metric always runs
-- (backward compatible; all existing rows).
ALTER TABLE test_suite_metric_definitions
    ADD COLUMN IF NOT EXISTS condition VARCHAR(2000);
