-- Computes the ROC AUC score (rank-sum / Mann-Whitney formulation) for a binary classifier.
-- `y` holds the actual class (0/1) and `p` the predicted probability for the same row, paired
-- positionally by array index (both arrays MUST be built from the same row scan, e.g. via
-- array_agg(y), array_agg(p) in the same SELECT). Returns NULL when either class is absent, since
-- no positive/negative pair exists to rank.
CREATE FUNCTION roc_auc_score(y double precision[], p double precision[])
RETURNS double precision
LANGUAGE sql
IMMUTABLE
AS $$
    WITH predictions AS (
        SELECT yv.value AS y, pv.value AS p
        FROM unnest(y) WITH ORDINALITY AS yv(value, ord)
        JOIN unnest(p) WITH ORDINALITY AS pv(value, ord) ON yv.ord = pv.ord
    ),
    ranked AS (
        SELECT
            y,
            AVG(rn) OVER (PARTITION BY rank_group) AS avg_rank
        FROM (
            SELECT
                y,
                ROW_NUMBER() OVER (ORDER BY p) AS rn,
                DENSE_RANK() OVER (ORDER BY p) AS rank_group
            FROM predictions
        ) sub
    ),
    stats AS (
        SELECT
            SUM(avg_rank) FILTER (WHERE y = 1) AS rank_sum_pos,
            COUNT(*) FILTER (WHERE y = 1) AS n_pos,
            COUNT(*) FILTER (WHERE y = 0) AS n_neg
        FROM ranked
    )
    SELECT (rank_sum_pos - n_pos * (n_pos + 1) / 2.0) / NULLIF(n_pos * n_neg, 0)
    FROM stats;
$$;
