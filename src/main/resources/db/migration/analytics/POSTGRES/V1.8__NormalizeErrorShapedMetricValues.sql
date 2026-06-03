-- V1.8: Normalize error-shaped metricValues and metricInfos entries to use real output field names.
--
-- Transport failures previously produced {"tsmdName": {"error": null}} in metric_values and
-- {"tsmdName": {"error": "message"}} in metric_infos. This migration rewrites those entries to
-- use actual output field names from the corresponding run_metric_snapshots.output_schema.
--
-- Detection heuristics:
--   metric_values  : TSMD-level object contains exactly one key "error" with a JSON null value
--   metric_infos   : TSMD-level object contains exactly one key "error" whose value is a text string
--
-- Records without a matching snapshot or with empty output_schema.properties are left unchanged.
-- metric_infos IS NULL rows have their metric_infos column preserved as NULL (CASE WHEN guard).

WITH error_entries AS (
    -- Identify (eval_summary_id, tsmd_name) pairs that have the transport-failure shape in metric_values
    SELECT
        es.id                              AS eval_summary_id,
        es.created_at_ms,
        kv.key                             AS tsmd_name,
        -- Extract output field names from the matching snapshot's output_schema
        ARRAY(
            SELECT jsonb_object_keys(rms.output_schema -> 'properties')
            FROM run_metric_snapshots rms
            WHERE rms.computation_id = es.computation_id
              AND rms.tsmd_name      = kv.key
              AND rms.output_schema -> 'properties' IS NOT NULL
              AND rms.output_schema -> 'properties' <> '{}'::jsonb
            LIMIT 1
        )                                  AS field_names
    FROM test_case_eval_summaries es,
         jsonb_each(es.metric_values) AS kv
    -- Filter: TSMD entry has exactly one key "error" with a null value
    WHERE jsonb_typeof(kv.value -> 'error') = 'null'
      AND (SELECT count(*) FROM jsonb_object_keys(kv.value)) = 1
),
eligible AS (
    -- Only keep entries where we successfully resolved at least one field name
    SELECT eval_summary_id, created_at_ms, tsmd_name, field_names
    FROM error_entries
    WHERE array_length(field_names, 1) > 0
),
-- Aggregate per eval summary: rebuild the changed portion of metric_values and metric_infos
rebuilt_per_summary AS (
    SELECT
        eval_summary_id,
        created_at_ms,
        -- Rebuild metric_values: replace each error entry with {fieldName: null, ...}
        jsonb_object_agg(
            tsmd_name,
            (
                SELECT jsonb_object_agg(fn, 'null'::jsonb)
                FROM unnest(field_names) AS fn
            )
        ) AS new_mv_fragment,
        -- Rebuild metric_infos: replace each error entry with {fieldName: {"error": "message"}, ...}
        jsonb_object_agg(
            tsmd_name,
            (
                SELECT jsonb_object_agg(
                    fn,
                    jsonb_build_object('error', es_inner.metric_infos -> tsmd_name ->> 'error')
                )
                FROM unnest(field_names) AS fn,
                     test_case_eval_summaries es_inner
                WHERE es_inner.id            = eligible.eval_summary_id
                  AND es_inner.created_at_ms = eligible.created_at_ms
                  AND es_inner.metric_infos IS NOT NULL
                  AND jsonb_typeof(es_inner.metric_infos -> tsmd_name -> 'error') = 'string'
            )
        ) AS new_mi_fragment
    FROM eligible
    GROUP BY eval_summary_id, created_at_ms
)
UPDATE test_case_eval_summaries es
SET
    metric_values = es.metric_values || r.new_mv_fragment,
    metric_infos  = CASE
                        WHEN es.metric_infos IS NOT NULL AND r.new_mi_fragment IS NOT NULL
                            THEN es.metric_infos || r.new_mi_fragment
                        ELSE es.metric_infos
                    END
FROM rebuilt_per_summary r
WHERE es.id            = r.eval_summary_id
  AND es.created_at_ms = r.created_at_ms;
