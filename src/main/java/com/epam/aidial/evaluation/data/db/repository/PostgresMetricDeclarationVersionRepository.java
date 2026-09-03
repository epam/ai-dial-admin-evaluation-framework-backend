package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATIONS;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATION_VERSIONS;

import com.epam.aidial.evaluation.data.db.mapper.MetricDeclarationRecordMapper;
import com.epam.aidial.evaluation.data.db.mapper.MetricDeclarationVersionRecordMapper;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationWithLatestVersion;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;
import org.jooq.DSLContext;
import org.jooq.Field;
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
    private final MetricDeclarationRecordMapper declarationRecordMapper;
    private final TransactionTimestampContext transactionTimestampContext;

    /**
     * Inserts the version, assigning {@code MAX(schema_version) + 1} when the caller passes a
     * non-positive schema_version.
     *
     * <p>That assignment is deliberately <em>not</em> serialized with a lock on the parent declaration;
     * uq_metric_declaration_versions_declaration_version is the only guard. When two concurrent writers
     * both read MAX before either commits they compute the same schema_version, so one insert is rejected
     * with SQLSTATE 23505 and its transaction rolls back, leaving no partial data.
     *
     * <p>That guard is not airtight, and knowingly so. The meta transaction manager runs at the Postgres
     * default READ COMMITTED, where every statement takes a fresh snapshot, so a winner that commits
     * between the caller's change detection and the MAX read below leaves the loser computing a distinct
     * schema_version and inserting successfully - persisting a version whose schemas may duplicate the
     * winner's.
     *
     * <p>Locking would not close that case; it would make it certain. The caller's change detection
     * ({@code MetricProviderSyncService.differsFromLatest}) runs before the insert, so a waiter would
     * unblock, never re-check, and always persist the redundant version - and version ids are referenced
     * by test suite metric definitions, so the inflation is durable. A {@code SELECT ... FOR UPDATE} would
     * additionally conflict with the FOR KEY SHARE that FK checks take on metric_declarations, blocking
     * inserts into metric_declaration_versions (V1.9) and test_suite_metric_definitions (V1.13) that never
     * blocked before. A rare redundant version is the cheaper trade.
     *
     * <p>{@code MetricProviderSyncJob} therefore treats 23505 as an expected concurrent-sync outcome and
     * leaves recovery to the next sync run.
     */
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

    /**
     * Uses Postgres {@code SELECT DISTINCT ON} to pick, per metric_declaration_id, the first row of the
     * (metric_declaration_id, schema_version DESC) ordering - i.e. the greatest schema_version. That
     * ordering is exactly uq_metric_declaration_versions_declaration_version, so this is a single index
     * scan rather than the table self-join a MAX(schema_version) group-by would need. No tiebreaker
     * column is needed: the index is unique, so one (declaration, schema_version) matches at most one row.
     *
     * <p>Declarations that have no version row yet are omitted because the query drives FROM
     * metric_declaration_versions: such a declaration has no row to drive from. The join type is
     * irrelevant to that - leftJoin would return the same rows, since a version row cannot point at a
     * missing declaration (FK fk_metric_declaration_versions_declaration). The join is many-to-one on the
     * distinct key, so {@code DISTINCT ON} still yields exactly one row per declaration.
     *
     * <p>The joined record is split back into the two typed records with {@code record.into(TABLE)},
     * which resolves each target field by Field <em>identity</em> against the projection. That is what
     * makes the split unambiguous even though both tables carry an id, description, created_at_ms and
     * display_name column - so never alias a column here and never project an asterisk: either loses the
     * identity match and silently falls back to matching by unqualified column name.
     */
    @Override
    public List<MetricDeclarationWithLatestVersion> findLatestPerMetricDeclaration() {
        final Field<?>[] projection =
                ArrayUtils.addAll(METRIC_DECLARATIONS.fields(), METRIC_DECLARATION_VERSIONS.fields());

        return dsl.selectDistinct(projection)
                .on(METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID)
                .from(METRIC_DECLARATION_VERSIONS)
                .join(METRIC_DECLARATIONS)
                .on(METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID.eq(METRIC_DECLARATIONS.ID))
                .orderBy(
                        METRIC_DECLARATION_VERSIONS.METRIC_DECLARATION_ID,
                        METRIC_DECLARATION_VERSIONS.SCHEMA_VERSION.desc())
                .fetch(record -> new MetricDeclarationWithLatestVersion(
                        declarationRecordMapper.map(record.into(METRIC_DECLARATIONS)),
                        recordMapper.map(record.into(METRIC_DECLARATION_VERSIONS))));
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
