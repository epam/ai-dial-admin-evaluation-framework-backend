package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATION_VERSIONS;

import com.epam.aidial.evaluation.data.db.mapper.MetricDeclarationVersionRecordMapper;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresMetricDeclarationVersionRepository implements MetricDeclarationVersionRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final MetricDeclarationVersionRecordMapper recordMapper;
    private final TransactionTimestampContext transactionTimestampContext;

    @Override
    public MetricDeclarationVersion save(MetricDeclarationVersion version) {
        long createdAtMs = transactionTimestampContext.getTimestamp();
        if (version.getId() == null) {
            version.setId(UUID.randomUUID());
        }
        if (version.getCreatedAt() == null) {
            version.setCreatedAt(createdAtMs);
        }
        int schemaVersion = version.getSchemaVersion();
        if (schemaVersion <= 0) {
            Integer next = dsl.select(DSL.coalesce(DSL.max(METRIC_DECLARATION_VERSIONS.SCHEMA_VERSION), 0)
                            .add(1))
                    .from(METRIC_DECLARATION_VERSIONS)
                    .where(METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID.eq(
                            version.getMetricDeclarationId().toString()))
                    .fetchOne(0, Integer.class);
            schemaVersion = next != null ? next : 1;
            version.setSchemaVersion(schemaVersion);
        }

        dsl.insertInto(METRIC_DECLARATION_VERSIONS)
                .set(METRIC_DECLARATION_VERSIONS.ID, version.getId().toString())
                .set(
                        METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID,
                        version.getMetricDeclarationId().toString())
                .set(METRIC_DECLARATION_VERSIONS.SCHEMA_VERSION, schemaVersion)
                .set(METRIC_DECLARATION_VERSIONS.CONFIG_SCHEMA, toJsonb(version.getConfigSchema()))
                .set(METRIC_DECLARATION_VERSIONS.INPUT_SCHEMA, toJsonb(version.getInputSchema()))
                .set(METRIC_DECLARATION_VERSIONS.OUTPUT_SCHEMA, toJsonb(version.getOutputSchema()))
                .set(METRIC_DECLARATION_VERSIONS.DISPLAY_NAME, version.getDisplayName())
                .set(METRIC_DECLARATION_VERSIONS.DESCRIPTION, version.getDescription())
                .set(METRIC_DECLARATION_VERSIONS.CREATED_AT_MS, version.getCreatedAt())
                .execute();
        return version;
    }

    @Override
    public boolean existsByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId) {
        return dsl.fetchExists(
                METRIC_DECLARATION_VERSIONS,
                METRIC_DECLARATION_VERSIONS
                        .ID
                        .eq(id.toString())
                        .and(METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID.eq(metricDeclarationId.toString())));
    }

    @Override
    public Optional<MetricDeclarationVersion> findByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId) {
        return dsl.selectFrom(METRIC_DECLARATION_VERSIONS)
                .where(METRIC_DECLARATION_VERSIONS
                        .ID
                        .eq(id.toString())
                        .and(METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID.eq(metricDeclarationId.toString())))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public Optional<MetricDeclarationVersion> findLatestByMetricDeclarationId(UUID metricDeclarationId) {
        return dsl.selectFrom(METRIC_DECLARATION_VERSIONS)
                .where(METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID.eq(metricDeclarationId.toString()))
                .orderBy(METRIC_DECLARATION_VERSIONS.SCHEMA_VERSION.desc())
                .limit(1)
                .fetchOptional(recordMapper::map);
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
