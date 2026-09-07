-- Converts test_case_run_results, test_case_eval_summaries, and test_case_eval_scores into
-- native PostgreSQL declarative RANGE partitions on created_at_ms, monthly, UTC-aligned.
-- See openspec/changes/partition-analytics-tables/design.md for full rationale.
--
-- test_case_run_results / test_case_eval_summaries: zero-copy migration. Their PK and unique
-- constraint already end in created_at_ms (added in V1.1/V1.5/V1.17/V1.18 for exactly this),
-- so the existing table is simply renamed and ATTACHed as a "_p_legacy" partition.
--
-- test_case_eval_scores: did not start with a time column, so it additionally gets a new
-- created_at_ms column backfilled from its parent summary row, and its PK changes from
-- (eval_summary_id) to (created_at_ms, eval_summary_id) before the same ATTACH treatment.
-- This is the one genuinely data-touching step in this migration (see design.md Risks).
--
-- All three blocks compute their cutover the same way (start of the UTC month after "now"),
-- which is stable within this single migration transaction, so all three tables' partitions
-- land on identical month boundaries. Each block is guarded to be a no-op if its table is
-- already partitioned, so this migration is safe to re-run or to have been pre-applied.
--
-- DST note: all month-bound arithmetic below operates on naive ("timestamp without time zone")
-- values representing UTC wall-clock time, converting to timestamptz only at the point of
-- extracting an epoch-millis value. This is deliberate: "timestamptz + interval 'N months'"
-- arithmetic in Postgres is performed using the SESSION's timezone calendar (to apply
-- calendar-unit intervals correctly), so if the connection's timezone isn't UTC, adding months
-- across a DST transition in that timezone silently shifts the result by an hour relative to
-- true UTC month boundaries. Naive-timestamp arithmetic has no timezone attached and therefore
-- no DST rules to apply, avoiding this entirely.
--
-- Lock note: this migration runs inside a single Flyway transaction. Locks acquired by the
-- earlier RENAME/ADD CONSTRAINT statements in each block are held for the rest of that
-- transaction, so the NOT VALID + VALIDATE CONSTRAINT split below does not actually reduce
-- the effective lock level during this single-transaction execution — it is structured this
-- way so the two steps can be pulled apart into a separate pre-migration step (validating the
-- CHECK ahead of time, outside any transaction) for a deployment where the exclusive-lock
-- window must be minimized; see design.md D3's rejected "split runbook" alternative.

-- ==================== test_case_run_results ====================
DO $$
DECLARE
    v_relkind           "char";
    v_cutover_naive     timestamp;
    v_cutover_ms        bigint;
    v_month_start_naive timestamp;
    v_month_end_naive   timestamp;
    v_lower_ms          bigint;
    v_upper_ms          bigint;
    v_partition_name    text;
    v_look_ahead        integer := 2;
    i                   integer;
BEGIN
    SELECT c.relkind INTO v_relkind
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relname = 'test_case_run_results' AND n.nspname = current_schema();

    IF v_relkind = 'p' THEN
        RAISE NOTICE 'test_case_run_results is already partitioned; skipping';
    ELSE
        v_cutover_naive := date_trunc('month', now() AT TIME ZONE 'UTC') + interval '1 month';
        v_cutover_ms := (extract(epoch FROM (v_cutover_naive AT TIME ZONE 'UTC')) * 1000)::bigint;

        -- Free up the original names by renaming the existing table and its constraints/indexes.
        ALTER TABLE test_case_run_results RENAME TO test_case_run_results_p_legacy;
        ALTER TABLE test_case_run_results_p_legacy
            RENAME CONSTRAINT test_case_run_results_pkey TO test_case_run_results_p_legacy_pkey;
        ALTER TABLE test_case_run_results_p_legacy
            RENAME CONSTRAINT uq_results_run_case_index TO uq_results_run_case_index_p_legacy;
        ALTER INDEX idx_results_suite_run_case RENAME TO idx_results_suite_run_case_p_legacy;
        ALTER INDEX idx_results_id RENAME TO idx_results_id_p_legacy;

        -- New empty partitioned parent, same shape, original names — inherited by every partition.
        CREATE TABLE test_case_run_results
            (LIKE test_case_run_results_p_legacy INCLUDING DEFAULTS INCLUDING COMMENTS)
            PARTITION BY RANGE (created_at_ms);

        ALTER TABLE test_case_run_results
            ADD CONSTRAINT test_case_run_results_pkey PRIMARY KEY (created_at_ms, id);
        ALTER TABLE test_case_run_results
            ADD CONSTRAINT uq_results_run_case_index
                UNIQUE (test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms);
        CREATE INDEX idx_results_suite_run_case
            ON test_case_run_results (test_suite_id, test_suite_run_id, test_case_name);
        CREATE INDEX idx_results_id
            ON test_case_run_results (id);

        EXECUTE format(
            'ALTER TABLE test_case_run_results_p_legacy ADD CONSTRAINT ck_results_p_legacy_upper CHECK (created_at_ms < %L) NOT VALID',
            v_cutover_ms);
        ALTER TABLE test_case_run_results_p_legacy VALIDATE CONSTRAINT ck_results_p_legacy_upper;

        EXECUTE format(
            'ALTER TABLE test_case_run_results ATTACH PARTITION test_case_run_results_p_legacy FOR VALUES FROM (MINVALUE) TO (%L)',
            v_cutover_ms);

        -- Bootstrap the next v_look_ahead months; AnalyticsPartitionMaintenanceJob takes over
        -- ongoing creation/retention from here based on the configured look-ahead-months.
        FOR i IN 0..(v_look_ahead - 1) LOOP
            v_month_start_naive := v_cutover_naive + (i || ' months')::interval;
            v_month_end_naive := v_month_start_naive + interval '1 month';
            v_lower_ms := (extract(epoch FROM (v_month_start_naive AT TIME ZONE 'UTC')) * 1000)::bigint;
            v_upper_ms := (extract(epoch FROM (v_month_end_naive AT TIME ZONE 'UTC')) * 1000)::bigint;
            v_partition_name := 'test_case_run_results_p' || to_char(v_month_start_naive, 'YYYYMM');

            EXECUTE format(
                'CREATE TABLE %I PARTITION OF test_case_run_results FOR VALUES FROM (%L) TO (%L)',
                v_partition_name, v_lower_ms, v_upper_ms);
            EXECUTE format(
                'COMMENT ON TABLE %I IS %L',
                v_partition_name, 'UTC month ' || to_char(v_month_start_naive, 'YYYY-MM'));
        END LOOP;

        CREATE TABLE test_case_run_results_p_default PARTITION OF test_case_run_results DEFAULT;
    END IF;
END $$;

-- ==================== test_case_eval_summaries ====================
DO $$
DECLARE
    v_relkind           "char";
    v_cutover_naive     timestamp;
    v_cutover_ms        bigint;
    v_month_start_naive timestamp;
    v_month_end_naive   timestamp;
    v_lower_ms          bigint;
    v_upper_ms          bigint;
    v_partition_name    text;
    v_look_ahead        integer := 2;
    i                   integer;
BEGIN
    SELECT c.relkind INTO v_relkind
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relname = 'test_case_eval_summaries' AND n.nspname = current_schema();

    IF v_relkind = 'p' THEN
        RAISE NOTICE 'test_case_eval_summaries is already partitioned; skipping';
    ELSE
        v_cutover_naive := date_trunc('month', now() AT TIME ZONE 'UTC') + interval '1 month';
        v_cutover_ms := (extract(epoch FROM (v_cutover_naive AT TIME ZONE 'UTC')) * 1000)::bigint;

        ALTER TABLE test_case_eval_summaries RENAME TO test_case_eval_summaries_p_legacy;
        ALTER TABLE test_case_eval_summaries_p_legacy
            RENAME CONSTRAINT test_case_eval_summaries_pkey TO test_case_eval_summaries_p_legacy_pkey;
        ALTER INDEX uq_eval_summaries_natural_key RENAME TO uq_eval_summaries_natural_key_p_legacy;
        ALTER INDEX idx_eval_summaries_run_computation RENAME TO idx_eval_summaries_run_computation_p_legacy;
        ALTER INDEX idx_eval_summaries_computation RENAME TO idx_eval_summaries_computation_p_legacy;
        ALTER INDEX idx_eval_summaries_id RENAME TO idx_eval_summaries_id_p_legacy;
        ALTER INDEX idx_eval_summaries_run_computed_at RENAME TO idx_eval_summaries_run_computed_at_p_legacy;

        CREATE TABLE test_case_eval_summaries
            (LIKE test_case_eval_summaries_p_legacy INCLUDING DEFAULTS INCLUDING COMMENTS)
            PARTITION BY RANGE (created_at_ms);

        ALTER TABLE test_case_eval_summaries
            ADD CONSTRAINT test_case_eval_summaries_pkey PRIMARY KEY (created_at_ms, id);
        CREATE UNIQUE INDEX uq_eval_summaries_natural_key
            ON test_case_eval_summaries (test_suite_run_id, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms);
        CREATE INDEX idx_eval_summaries_run_computation
            ON test_case_eval_summaries (test_suite_run_id, computation_id);
        CREATE INDEX idx_eval_summaries_computation
            ON test_case_eval_summaries (computation_id);
        CREATE INDEX idx_eval_summaries_id
            ON test_case_eval_summaries (id);
        CREATE INDEX idx_eval_summaries_run_computed_at
            ON test_case_eval_summaries (test_suite_run_id, computed_at_ms DESC, computation_id);

        EXECUTE format(
            'ALTER TABLE test_case_eval_summaries_p_legacy ADD CONSTRAINT ck_eval_summaries_p_legacy_upper CHECK (created_at_ms < %L) NOT VALID',
            v_cutover_ms);
        ALTER TABLE test_case_eval_summaries_p_legacy VALIDATE CONSTRAINT ck_eval_summaries_p_legacy_upper;

        EXECUTE format(
            'ALTER TABLE test_case_eval_summaries ATTACH PARTITION test_case_eval_summaries_p_legacy FOR VALUES FROM (MINVALUE) TO (%L)',
            v_cutover_ms);

        FOR i IN 0..(v_look_ahead - 1) LOOP
            v_month_start_naive := v_cutover_naive + (i || ' months')::interval;
            v_month_end_naive := v_month_start_naive + interval '1 month';
            v_lower_ms := (extract(epoch FROM (v_month_start_naive AT TIME ZONE 'UTC')) * 1000)::bigint;
            v_upper_ms := (extract(epoch FROM (v_month_end_naive AT TIME ZONE 'UTC')) * 1000)::bigint;
            v_partition_name := 'test_case_eval_summaries_p' || to_char(v_month_start_naive, 'YYYYMM');

            EXECUTE format(
                'CREATE TABLE %I PARTITION OF test_case_eval_summaries FOR VALUES FROM (%L) TO (%L)',
                v_partition_name, v_lower_ms, v_upper_ms);
            EXECUTE format(
                'COMMENT ON TABLE %I IS %L',
                v_partition_name, 'UTC month ' || to_char(v_month_start_naive, 'YYYY-MM'));
        END LOOP;

        CREATE TABLE test_case_eval_summaries_p_default PARTITION OF test_case_eval_summaries DEFAULT;
    END IF;
END $$;

-- ==================== test_case_eval_scores ====================
DO $$
DECLARE
    v_relkind           "char";
    v_cutover_naive     timestamp;
    v_cutover_ms        bigint;
    v_month_start_naive timestamp;
    v_month_end_naive   timestamp;
    v_lower_ms          bigint;
    v_upper_ms          bigint;
    v_partition_name    text;
    v_look_ahead        integer := 2;
    i                   integer;
BEGIN
    SELECT c.relkind INTO v_relkind
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relname = 'test_case_eval_scores' AND n.nspname = current_schema();

    IF v_relkind = 'p' THEN
        RAISE NOTICE 'test_case_eval_scores is already partitioned; skipping';
    ELSE
        v_cutover_naive := date_trunc('month', now() AT TIME ZONE 'UTC') + interval '1 month';
        v_cutover_ms := (extract(epoch FROM (v_cutover_naive AT TIME ZONE 'UTC')) * 1000)::bigint;

        -- Defensive: scores are documented as regenerable derived data (see V1.19 comment),
        -- so an orphaned row with no matching summary is safe to drop rather than block the
        -- backfill below with an unbackfillable NULL.
        DELETE FROM test_case_eval_scores s
        WHERE NOT EXISTS (SELECT 1 FROM test_case_eval_summaries e WHERE e.id = s.eval_summary_id);

        ALTER TABLE test_case_eval_scores ADD COLUMN created_at_ms BIGINT;

        -- The one genuinely data-touching step in this migration: a real backfill (not a
        -- cheap sentinel) so the eval_summaries JOIN can also match on created_at_ms and keep
        -- today's join-elimination optimization. See design.md D3/D5a and the Risks section.
        UPDATE test_case_eval_scores s
        SET created_at_ms = e.created_at_ms
        FROM test_case_eval_summaries e
        WHERE s.eval_summary_id = e.id;

        ALTER TABLE test_case_eval_scores ALTER COLUMN created_at_ms SET NOT NULL;

        ALTER TABLE test_case_eval_scores DROP CONSTRAINT test_case_eval_scores_pkey;
        ALTER TABLE test_case_eval_scores RENAME TO test_case_eval_scores_p_legacy;

        -- Flyway runs this migration inside a transaction, so CREATE UNIQUE INDEX CONCURRENTLY
        -- is not usable here; this plain build is bounded by the post-backfill row count and is
        -- pre-built so the subsequent ATTACH adopts it instead of building its own.
        CREATE UNIQUE INDEX test_case_eval_scores_p_legacy_pkey
            ON test_case_eval_scores_p_legacy (created_at_ms, eval_summary_id);

        CREATE TABLE test_case_eval_scores
            (LIKE test_case_eval_scores_p_legacy INCLUDING DEFAULTS INCLUDING COMMENTS)
            PARTITION BY RANGE (created_at_ms);

        ALTER TABLE test_case_eval_scores
            ADD CONSTRAINT test_case_eval_scores_pkey PRIMARY KEY (created_at_ms, eval_summary_id);

        EXECUTE format(
            'ALTER TABLE test_case_eval_scores_p_legacy ADD CONSTRAINT ck_eval_scores_p_legacy_upper CHECK (created_at_ms < %L) NOT VALID',
            v_cutover_ms);
        ALTER TABLE test_case_eval_scores_p_legacy VALIDATE CONSTRAINT ck_eval_scores_p_legacy_upper;

        EXECUTE format(
            'ALTER TABLE test_case_eval_scores ATTACH PARTITION test_case_eval_scores_p_legacy FOR VALUES FROM (MINVALUE) TO (%L)',
            v_cutover_ms);

        -- Same month set as test_case_eval_summaries, since v_cutover_naive is derived identically
        -- from now() (stable within this single migration transaction).
        FOR i IN 0..(v_look_ahead - 1) LOOP
            v_month_start_naive := v_cutover_naive + (i || ' months')::interval;
            v_month_end_naive := v_month_start_naive + interval '1 month';
            v_lower_ms := (extract(epoch FROM (v_month_start_naive AT TIME ZONE 'UTC')) * 1000)::bigint;
            v_upper_ms := (extract(epoch FROM (v_month_end_naive AT TIME ZONE 'UTC')) * 1000)::bigint;
            v_partition_name := 'test_case_eval_scores_p' || to_char(v_month_start_naive, 'YYYYMM');

            EXECUTE format(
                'CREATE TABLE %I PARTITION OF test_case_eval_scores FOR VALUES FROM (%L) TO (%L)',
                v_partition_name, v_lower_ms, v_upper_ms);
            EXECUTE format(
                'COMMENT ON TABLE %I IS %L',
                v_partition_name, 'UTC month ' || to_char(v_month_start_naive, 'YYYY-MM'));
        END LOOP;

        CREATE TABLE test_case_eval_scores_p_default PARTITION OF test_case_eval_scores DEFAULT;
    END IF;
END $$;
