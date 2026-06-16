ALTER TABLE test_case_eval_summaries ADD COLUMN extraction_warnings JSONB NOT NULL DEFAULT '[]'::jsonb;
