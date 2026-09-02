package com.epam.aidial.evaluation.functional.helper;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATIONS;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATION_VERSIONS;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides test data for metric declarations (same semantics as previously seeded by migration).
 * Use in functional tests that need the default catalog: Accuracy, Latency, Relevance.
 *
 * <p>Every insert targets the primary key in its ON CONFLICT clause ({@code onConflict(ID).doNothing()})
 * rather than the untargeted {@code onDuplicateKeyIgnore()}: the latter renders a bare
 * {@code ON CONFLICT DO NOTHING}, which would also swallow a
 * uq_metric_declaration_versions_declaration_version violation and so mask the very conflict the
 * duplicate-schema_version test asserts.
 */
@RequiredArgsConstructor
public class MetricDeclarationTestDataProvider {

    /** Provider id used for seed metric declarations in functional tests. */
    public static final String SEED_METRIC_PROVIDER_ID = "test-provider";

    private static final String SEED_ACCURACY_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SEED_LATENCY_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SEED_RELEVANCE_ID = "00000000-0000-0000-0000-000000000003";

    private static final JSONB EMPTY_SCHEMA = JSONB.valueOf("{}");
    private static final JSONB SCORE_OUTPUT_SCHEMA =
            JSONB.valueOf("{\"properties\": {\"score\": {\"type\": \"number\"}}}");

    private final DSLContext metaDsl;

    /**
     * Inserts the same metric declarations that were previously seeded by migration (Accuracy, Latency,
     * Relevance) so that functional tests can assume this catalog data is present. Idempotent: safe to
     * call multiple times (ON CONFLICT DO NOTHING).
     */
    @Transactional("metaTransactionManager")
    public void insertSeedMetricDeclarations() {
        long createdAtMs = System.currentTimeMillis();
        metaDsl.insertInto(
                        METRIC_DECLARATIONS,
                        METRIC_DECLARATIONS.ID,
                        METRIC_DECLARATIONS.NAME,
                        METRIC_DECLARATIONS.DESCRIPTION,
                        METRIC_DECLARATIONS.CREATED_AT_MS,
                        METRIC_DECLARATIONS.PROVIDER_ID)
                .values(
                        SEED_ACCURACY_ID,
                        "Accuracy",
                        "Measures correctness of responses",
                        createdAtMs,
                        SEED_METRIC_PROVIDER_ID)
                .values(
                        SEED_LATENCY_ID,
                        "Latency",
                        "Measures response time in milliseconds",
                        createdAtMs,
                        SEED_METRIC_PROVIDER_ID)
                .values(
                        SEED_RELEVANCE_ID,
                        "Relevance",
                        "Measures relevance score",
                        createdAtMs,
                        SEED_METRIC_PROVIDER_ID)
                .onConflict(METRIC_DECLARATIONS.ID)
                .doNothing()
                .execute();
    }

    /**
     * Inserts one schema version for the Accuracy declaration so GET .../latest has data.
     * Call after insertSeedMetricDeclarations() when testing latest-version endpoint.
     */
    @Transactional("metaTransactionManager")
    public void insertSeedVersionForAccuracy() {
        insertVersion(
                "770e8400-e29b-41d4-a716-446655440001",
                SEED_ACCURACY_ID,
                1,
                EMPTY_SCHEMA,
                EMPTY_SCHEMA,
                SCORE_OUTPUT_SCHEMA,
                null,
                "Measures correctness of responses",
                System.currentTimeMillis());
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
        insertVersion(
                versionId,
                declarationId,
                schemaVersion,
                toJsonb(configSchema),
                toJsonb(inputSchema),
                toJsonb(outputSchema),
                null,
                "test",
                System.currentTimeMillis());
    }

    /**
     * Inserts one declaration with fully explicit metadata. Use together with
     * {@link #insertVersionWithMetadata} when a test must tell declaration-sourced response fields apart
     * from version-sourced ones (both tables carry id, display_name, description and created_at_ms).
     */
    @Transactional("metaTransactionManager")
    public void insertDeclarationWithMetadata(
            String id, String providerId, String name, String displayName, String description, long createdAtMs) {
        metaDsl.insertInto(METRIC_DECLARATIONS)
                .set(METRIC_DECLARATIONS.ID, id)
                .set(METRIC_DECLARATIONS.PROVIDER_ID, providerId)
                .set(METRIC_DECLARATIONS.NAME, name)
                .set(METRIC_DECLARATIONS.DISPLAY_NAME, displayName)
                .set(METRIC_DECLARATIONS.DESCRIPTION, description)
                .set(METRIC_DECLARATIONS.CREATED_AT_MS, createdAtMs)
                .onConflict(METRIC_DECLARATIONS.ID)
                .doNothing()
                .execute();
    }

    /**
     * Inserts one version with fully explicit metadata (empty schemas). Counterpart of
     * {@link #insertDeclarationWithMetadata}; pass values that differ from the declaration's so a test can
     * prove which table each response field came from.
     */
    @Transactional("metaTransactionManager")
    public void insertVersionWithMetadata(
            String versionId,
            String declarationId,
            int schemaVersion,
            String displayName,
            String description,
            long createdAtMs) {
        insertVersion(
                versionId,
                declarationId,
                schemaVersion,
                EMPTY_SCHEMA,
                EMPTY_SCHEMA,
                EMPTY_SCHEMA,
                displayName,
                description,
                createdAtMs);
    }

    /**
     * Clears all metric declarations and their versions. Use for "empty catalog" tests.
     */
    @Transactional("metaTransactionManager")
    public void clearMetricDeclarationsAndVersions() {
        metaDsl.deleteFrom(METRIC_DECLARATION_VERSIONS).execute();
        metaDsl.deleteFrom(METRIC_DECLARATIONS).execute();
    }

    /**
     * Inserts a single declaration without any version (for 404 "no versions" test).
     * Idempotent: safe to call multiple times (ON CONFLICT DO NOTHING).
     */
    @Transactional("metaTransactionManager")
    public void insertSingleDeclarationWithoutVersion(String id, String providerId, String name) {
        insertDeclarationWithMetadata(id, providerId, name, null, "", System.currentTimeMillis());
    }

    private void insertVersion(
            String versionId,
            String declarationId,
            int schemaVersion,
            JSONB configSchema,
            JSONB inputSchema,
            JSONB outputSchema,
            String displayName,
            String description,
            long createdAtMs) {
        metaDsl.insertInto(METRIC_DECLARATION_VERSIONS)
                .set(METRIC_DECLARATION_VERSIONS.ID, versionId)
                .set(METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID, declarationId)
                .set(METRIC_DECLARATION_VERSIONS.SCHEMA_VERSION, schemaVersion)
                .set(METRIC_DECLARATION_VERSIONS.CONFIG_SCHEMA, configSchema)
                .set(METRIC_DECLARATION_VERSIONS.INPUT_SCHEMA, inputSchema)
                .set(METRIC_DECLARATION_VERSIONS.OUTPUT_SCHEMA, outputSchema)
                .set(METRIC_DECLARATION_VERSIONS.DISPLAY_NAME, displayName)
                .set(METRIC_DECLARATION_VERSIONS.DESCRIPTION, description)
                .set(METRIC_DECLARATION_VERSIONS.CREATED_AT_MS, createdAtMs)
                .onConflict(METRIC_DECLARATION_VERSIONS.ID)
                .doNothing()
                .execute();
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
