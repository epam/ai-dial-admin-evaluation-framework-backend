package com.epam.aidial.evaluation.functional.helper;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides test data for metric declarations (same semantics as previously seeded by migration).
 * Use in functional tests that need the default catalog: Accuracy, Latency, Relevance.
 */
@RequiredArgsConstructor
public class MetricDeclarationTestDataProvider {

    /** Provider id used for seed metric declarations in functional tests. */
    public static final String SEED_METRIC_PROVIDER_ID = "test-provider";

    private static final String SEED_ACCURACY_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SEED_LATENCY_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SEED_RELEVANCE_ID = "00000000-0000-0000-0000-000000000003";

    private static final String INSERT_SEED_METRIC_DECLARATIONS_SQL = """
            INSERT INTO metric_declarations (id, name, description, created_at_ms, provider_id)
            VALUES
                (:id1, :name1, :desc1, :created_at_ms, :provider_id),
                (:id2, :name2, :desc2, :created_at_ms, :provider_id),
                (:id3, :name3, :desc3, :created_at_ms, :provider_id)
            ON CONFLICT (id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate metaJdbcTemplate;

    /**
     * Inserts the same metric declarations that were previously seeded by migration (Accuracy, Latency,
     * Relevance) so that functional tests can assume this catalog data is present. Idempotent: safe to
     * call multiple times (ON CONFLICT DO NOTHING).
     */
    @Transactional("metaTransactionManager")
    public void insertSeedMetricDeclarations() {
        long createdAtMs = System.currentTimeMillis();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id1", SEED_ACCURACY_ID)
                .addValue("name1", "Accuracy")
                .addValue("desc1", "Measures correctness of responses")
                .addValue("id2", SEED_LATENCY_ID)
                .addValue("name2", "Latency")
                .addValue("desc2", "Measures response time in milliseconds")
                .addValue("id3", SEED_RELEVANCE_ID)
                .addValue("name3", "Relevance")
                .addValue("desc3", "Measures relevance score")
                .addValue("created_at_ms", createdAtMs)
                .addValue("provider_id", SEED_METRIC_PROVIDER_ID);
        metaJdbcTemplate.update(INSERT_SEED_METRIC_DECLARATIONS_SQL, params);
    }

    /**
     * Inserts one schema version for the Accuracy declaration so GET .../latest has data.
     * Call after insertSeedMetricDeclarations() when testing latest-version endpoint.
     */
    @Transactional("metaTransactionManager")
    public void insertSeedVersionForAccuracy() {
        long createdAtMs = System.currentTimeMillis();
        String versionId = "770e8400-e29b-41d4-a716-446655440001";
        metaJdbcTemplate.update(
                """
                INSERT INTO metric_declaration_versions (
                    id, metric_declaration_id, schema_version,
                    config_schema, input_schema, output_schema, description, created_at_ms
                ) VALUES (
                    :id, :declarationId, 1,
                    '{}', '{}',
                    '{"properties": {"score": {"type": "number"}}}'::jsonb,
                    'Measures correctness of responses', :createdAtMs
                )
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", versionId)
                        .addValue("declarationId", SEED_ACCURACY_ID)
                        .addValue("createdAtMs", createdAtMs));
    }

    /**
     * Inserts a version with explicit schema values (may be null) for testing schema serialization.
     */
    @Transactional("metaTransactionManager")
    public void insertVersionWithSchemas(
            String versionId,
            String declarationId,
            int schemaVersion,
            String configSchema,
            String inputSchema,
            String outputSchema) {
        long createdAtMs = System.currentTimeMillis();
        metaJdbcTemplate.update(
                """
                INSERT INTO metric_declaration_versions (
                    id, metric_declaration_id, schema_version,
                    config_schema, input_schema, output_schema, description, created_at_ms
                ) VALUES (
                    :id, :declarationId, :schemaVersion,
                    :configSchema::jsonb, :inputSchema::jsonb, :outputSchema::jsonb, 'test', :createdAtMs
                )
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", versionId)
                        .addValue("declarationId", declarationId)
                        .addValue("schemaVersion", schemaVersion)
                        .addValue("configSchema", configSchema)
                        .addValue("inputSchema", inputSchema)
                        .addValue("outputSchema", outputSchema)
                        .addValue("createdAtMs", createdAtMs));
    }

    /**
     * Clears all metric declarations and their versions. Use for "empty catalog" tests.
     */
    @Transactional("metaTransactionManager")
    public void clearMetricDeclarationsAndVersions() {
        metaJdbcTemplate.update("DELETE FROM metric_declaration_versions", new MapSqlParameterSource());
        metaJdbcTemplate.update("DELETE FROM metric_declarations", new MapSqlParameterSource());
    }

    /**
     * Inserts a single declaration without any version (for 404 "no versions" test).
     * Idempotent: safe to call multiple times (ON CONFLICT DO NOTHING).
     */
    @Transactional("metaTransactionManager")
    public void insertSingleDeclarationWithoutVersion(String id, String providerId, String name) {
        long createdAtMs = System.currentTimeMillis();
        metaJdbcTemplate.update(
                """
                INSERT INTO metric_declarations (id, provider_id, name, description, created_at_ms)
                VALUES (:id, :providerId, :name, '', :createdAtMs)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("providerId", providerId)
                        .addValue("name", name)
                        .addValue("createdAtMs", createdAtMs));
    }
}
