ALTER TABLE test_suites ADD COLUMN response_columns JSONB NOT NULL DEFAULT '[]'::jsonb;
