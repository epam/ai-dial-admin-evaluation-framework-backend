-- Per-row overall score/pass-fail, computed via SQL (reusing OverallScoreDefinitionResolver with an
-- id IN (...) + GROUP BY id graft) right after the corresponding test_case_eval_summaries batch is
-- written. A row's absence here (LEFT JOIN miss on read) and a present row with score = NULL (e.g. a
-- population-dependent CustomFunction like roc_auc degenerating on a single-row group) read
-- identically to a client, by design. No denormalized run/computation/test-case context: every read
-- goes through a join to test_case_eval_summaries, so eval_summary_id is the only key needed.
CREATE TABLE test_case_eval_scores (
    eval_summary_id      VARCHAR(36)      NOT NULL,
    score                DOUBLE PRECISION,
    passed               BOOLEAN,
    computed_at_ms       BIGINT           NOT NULL,
    PRIMARY KEY (eval_summary_id)
);
