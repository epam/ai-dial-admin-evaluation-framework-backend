package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Tables.RUN_METRIC_SNAPSHOTS;

import com.epam.aidial.evaluation.data.db.analytics.mapper.RunMetricSnapshotRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresRunMetricSnapshotRepository}. Reads are inherited unchanged. Only
 * {@link #saveAll} differs: ClickHouse has no {@code ON CONFLICT}; deduplication is delegated to the
 * {@code ReplacingMergeTree} table engine (ordered by the same natural key used for the Postgres
 * {@code onConflict}), made visible to readers via the {@code clickhouse_setting_final=1} connection
 * property (not a session-wide {@code SET}, which does not persist across statements on the
 * ClickHouse V2 HTTP driver — see {@code AnalyticsClickHouseConfiguration}'s Javadoc for the verified
 * mechanism, the single source of truth).
 */
@Slf4j
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class ClickHouseRunMetricSnapshotRepository extends PostgresRunMetricSnapshotRepository {

    public ClickHouseRunMetricSnapshotRepository(
            @Qualifier("analyticsDsl") DSLContext dsl, RunMetricSnapshotRecordMapper recordMapper) {
        super(dsl, recordMapper);
    }

    @Override
    public void saveAll(List<RunMetricSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        // Bind-value batch, never dsl.batch(List<Query>) — the multi-query batch inlines parameters and
        // ClickHouse interprets backslash escapes in string literals, corrupting escaped JSON payloads.
        // See docs/patterns/clickhouse-analytics.md.
        BatchBindStep batch = dsl.batch(dsl.insertInto(
                        RUN_METRIC_SNAPSHOTS,
                        RUN_METRIC_SNAPSHOTS.ID,
                        RUN_METRIC_SNAPSHOTS.COMPUTATION_ID,
                        RUN_METRIC_SNAPSHOTS.TEST_SUITE_RUN_ID,
                        RUN_METRIC_SNAPSHOTS.TSMD_ID,
                        RUN_METRIC_SNAPSHOTS.TSMD_NAME,
                        RUN_METRIC_SNAPSHOTS.METRIC_DECLARATION_ID,
                        RUN_METRIC_SNAPSHOTS.METRIC_DECLARATION_VERSION_ID,
                        RUN_METRIC_SNAPSHOTS.CONFIG_BINDINGS,
                        RUN_METRIC_SNAPSHOTS.INPUT_BINDINGS,
                        RUN_METRIC_SNAPSHOTS.OUTPUT_SCHEMA,
                        RUN_METRIC_SNAPSHOTS.COMPUTED_AT_MS)
                .values(
                        (String) null,
                        (String) null,
                        (String) null,
                        (String) null,
                        (String) null,
                        (String) null,
                        (String) null,
                        (JSONB) null,
                        (JSONB) null,
                        (JSONB) null,
                        (Long) null));
        for (RunMetricSnapshot s : snapshots) {
            batch = batch.bind(
                    s.getId().toString(),
                    s.getComputationId().toString(),
                    s.getTestSuiteRunId().toString(),
                    s.getTsmdId().toString(),
                    s.getTsmdName(),
                    s.getMetricDeclarationId().toString(),
                    s.getMetricDeclarationVersionId().toString(),
                    toJsonb(s.getConfigBindings()),
                    toJsonb(s.getInputBindings()),
                    toJsonb(s.getOutputSchema()),
                    s.getComputedAtMs());
        }
        batch.execute();
        log.debug("Batch inserted {} run metric snapshots", snapshots.size());
    }
}
