-- Consolidated analytics schema for datasource.analytics.vendor=CLICKHOUSE.
--
-- Column names and semantics mirror the POSTGRES analytics schema exactly (source of truth:
-- src/main/resources/db/migration/analytics/POSTGRES/ V1.1-V1.18 and docs/database-schema.md) so
-- that the same repository/service code can serve either vendor.
--
-- Two vendor-specific decisions apply to every table below:
--   1. ReplacingMergeTree-as-ON-CONFLICT: ClickHouse has no UPSERT / ON CONFLICT DO NOTHING.
--      ReplacingMergeTree keeps, per ORDER BY key, only the row with the highest value in the
--      table's implicit last ordinary column (none declared here, so the physically-last-inserted
--      row for a key wins after a background merge). Analytics repositories therefore do a plain
--      INSERT for every write (never an upsert), and reads rely on the analytics datasource's
--      "clickhouse_setting_final=1" connection property (see AnalyticsClickHouseConfiguration --
--      NOT a session-wide "SET final = 1", which does not persist across statements on the
--      ClickHouse V2 HTTP driver) to collapse duplicates deterministically before merges run,
--      rather than the PG unique-constraint + ON CONFLICT idiom.
--   2. String, not JSON: JSONB payload columns (test_case_data, request_body, response_body,
--      extracted_columns, extraction_warnings, metric_values, metric_infos, config_bindings,
--      input_bindings, output_schema, log_details) are declared as String/Nullable(String), not
--      ClickHouse's native JSON type. This keeps the byte-for-byte serialized representation
--      produced by the application's ObjectMapper as the single source of truth and avoids
--      ClickHouse's JSON type re-serializing (and thus mutating) payloads on read.
--
-- ClickHouse DDL statements auto-commit individually; each CREATE TABLE below is one statement.
--
-- This script is applied by ClickHouseSchemaInitializer, NOT by Flyway (the ClickHouse Flyway plugin
-- cannot run on the V2 JDBC driver -- see that class's Javadoc). There is therefore no schema-history
-- table and the script is re-executed on every startup, so every statement MUST be idempotent: use
-- CREATE TABLE IF NOT EXISTS / ALTER TABLE ... ADD COLUMN IF NOT EXISTS. Files in this directory are
-- applied in filename order.

CREATE TABLE IF NOT EXISTS test_case_run_results
(
    id                     String,
    test_suite_run_id      String,
    test_suite_id          String,
    test_case_id           String,
    test_case_name         String,
    run_index              Int32,
    request_index          Int32 DEFAULT 0,
    total_requests         Int32 DEFAULT 1,
    turn_index             Int32 DEFAULT 0,
    total_turns            Int32 DEFAULT 1,
    test_case_data         String,
    request_body           Nullable(String),
    response_body          Nullable(String),
    response_status_code   Nullable(Int32),
    execution_status       LowCardinality(String),
    exec_started_at_ms     Int64,
    exec_completed_at_ms   Int64,
    exec_duration_ms       Int64,
    retry_count            Int32 DEFAULT 0,
    log_details            Nullable(String),
    trace_id               Nullable(String),
    extracted_columns      String DEFAULT '{}',
    extraction_warnings    String DEFAULT '[]',
    created_at_ms          Int64,
    INDEX idx_id id TYPE bloom_filter GRANULARITY 4
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(fromUnixTimestamp64Milli(created_at_ms))
ORDER BY (test_suite_id, test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms);

CREATE TABLE IF NOT EXISTS test_case_eval_summaries
(
    id                      String,
    test_suite_id           String,
    test_suite_run_id       String,
    test_case_run_result_id String,
    test_case_id            String,
    test_case_name          String,
    run_index               Int32,
    request_index           Int32 DEFAULT 0,
    total_requests          Int32 DEFAULT 1,
    turn_index              Int32 DEFAULT 0,
    total_turns             Int32 DEFAULT 1,
    computation_id          String,
    test_case_data          String,
    extracted_columns       String DEFAULT '{}',
    execution_status        LowCardinality(String),
    exec_duration_ms        Int64,
    metric_eval_duration_ms Int64 DEFAULT 0,
    response_status_code    Nullable(Int32),
    metric_values           String DEFAULT '{}',
    metric_infos            Nullable(String),
    extraction_warnings     String DEFAULT '[]',
    created_at_ms           Int64,
    computed_at_ms          Int64,
    INDEX idx_id id TYPE bloom_filter GRANULARITY 4
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(fromUnixTimestamp64Milli(created_at_ms))
ORDER BY (test_suite_run_id, computation_id, test_case_id, run_index, request_index, turn_index, created_at_ms);

CREATE TABLE IF NOT EXISTS run_metric_snapshots
(
    id                             String,
    computation_id                 String,
    test_suite_run_id              String,
    tsmd_id                        String,
    tsmd_name                      String,
    metric_declaration_id          String,
    metric_declaration_version_id  String,
    config_bindings                String DEFAULT '[]',
    input_bindings                 String DEFAULT '[]',
    output_schema                  String DEFAULT '{}',
    computed_at_ms                 Int64
)
ENGINE = ReplacingMergeTree
ORDER BY (computation_id, tsmd_id);

CREATE TABLE IF NOT EXISTS metric_score_result
(
    id                  String,
    test_suite_run_id   String,
    test_suite_id       String,
    computation_id       String,
    metric_score_name    String,
    metric_name          String,
    value                Nullable(Float64),
    computed_at_ms       Int64
)
ENGINE = ReplacingMergeTree
ORDER BY (test_suite_run_id, computation_id, metric_score_name, metric_name);
