-- Per-suite test-case selection filter. NULL means "no filter" — the suite runs every valid,
-- non-disabled test case of its dataset (prior behavior). A non-null value is a self-contained
-- Structured Query DSL `filter` subtree authored over the dataset's test-case fields (base columns
-- and flattened `data::<field>` fields); it is AND-combined with `is_valid` and
-- `disabled_test_case_ids` to select the runnable test cases at run-creation count and snapshot.
-- Validated at suite write time against the bound dataset's test-case schema.
ALTER TABLE test_suites ADD COLUMN test_case_filter JSONB;
