ALTER TABLE test_case_run_results ADD COLUMN extracted_columns JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE test_case_run_results ADD COLUMN extraction_warnings JSONB NOT NULL DEFAULT '[]'::jsonb;
