-- Backfill a stable server-assigned `id` onto every field of datasets.test_case_schema.
-- Pure data fix: fills missing or null ids, keeps array order, leaves existing string ids untouched,
-- idempotent. Only JSON object elements get an id; non-object elements pass through unchanged. Does not bump version/updated_at_ms. The WHERE uses CASE because Postgres does not
-- guarantee AND short-circuiting and jsonb_array_elements must never run on a non-array value.
UPDATE datasets d
SET test_case_schema = (
    SELECT jsonb_agg(CASE WHEN jsonb_typeof(e.elem) = 'object'
                               AND jsonb_typeof(e.elem -> 'id') IS DISTINCT FROM 'string'
                          THEN e.elem || jsonb_build_object('id', gen_random_uuid()::text)
                          ELSE e.elem END
                     ORDER BY e.ord)
    FROM jsonb_array_elements(d.test_case_schema) WITH ORDINALITY AS e(elem, ord))
WHERE CASE WHEN jsonb_typeof(d.test_case_schema) = 'array'
           THEN EXISTS (SELECT 1 FROM jsonb_array_elements(d.test_case_schema) x
                        WHERE jsonb_typeof(x) = 'object'
                          AND jsonb_typeof(x -> 'id') IS DISTINCT FROM 'string')
           ELSE false END;
