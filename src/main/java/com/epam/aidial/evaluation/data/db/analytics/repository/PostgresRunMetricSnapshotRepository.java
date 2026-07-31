package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.RUN_METRIC_SNAPSHOTS;

import com.epam.aidial.evaluation.data.db.analytics.mapper.RunMetricSnapshotRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresRunMetricSnapshotRepository implements RunMetricSnapshotRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final RunMetricSnapshotRecordMapper recordMapper;

    @Override
    public void saveAll(List<RunMetricSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        List<Query> queries = snapshots.stream()
                .map(s -> (Query) dsl.insertInto(RUN_METRIC_SNAPSHOTS)
                        .set(RUN_METRIC_SNAPSHOTS.ID, s.getId().toString())
                        .set(
                                RUN_METRIC_SNAPSHOTS.COMPUTATION_ID,
                                s.getComputationId().toString())
                        .set(
                                RUN_METRIC_SNAPSHOTS.TEST_SUITE_RUN_ID,
                                s.getTestSuiteRunId().toString())
                        .set(RUN_METRIC_SNAPSHOTS.TSMD_ID, s.getTsmdId().toString())
                        .set(RUN_METRIC_SNAPSHOTS.TSMD_NAME, s.getTsmdName())
                        .set(
                                RUN_METRIC_SNAPSHOTS.METRIC_DECLARATION_ID,
                                s.getMetricDeclarationId().toString())
                        .set(
                                RUN_METRIC_SNAPSHOTS.METRIC_DECLARATION_VERSION_ID,
                                s.getMetricDeclarationVersionId().toString())
                        .set(RUN_METRIC_SNAPSHOTS.CONFIG_BINDINGS, toJsonb(s.getConfigBindings()))
                        .set(RUN_METRIC_SNAPSHOTS.INPUT_BINDINGS, toJsonb(s.getInputBindings()))
                        .set(RUN_METRIC_SNAPSHOTS.OUTPUT_SCHEMA, toJsonb(s.getOutputSchema()))
                        .set(RUN_METRIC_SNAPSHOTS.COMPUTED_AT_MS, s.getComputedAtMs())
                        .onConflict(RUN_METRIC_SNAPSHOTS.COMPUTATION_ID, RUN_METRIC_SNAPSHOTS.TSMD_ID)
                        .doNothing())
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch inserted {} run metric snapshots", snapshots.size());
    }

    @Override
    public List<RunMetricSnapshot> findByRunId(UUID runId) {
        return dsl.selectFrom(RUN_METRIC_SNAPSHOTS)
                .where(RUN_METRIC_SNAPSHOTS.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .orderBy(RUN_METRIC_SNAPSHOTS.COMPUTED_AT_MS.desc())
                .fetch(recordMapper::map);
    }

    @Override
    public List<RunMetricSnapshot> findByRunIdAndComputationId(UUID runId, UUID computationId) {
        return dsl.selectFrom(RUN_METRIC_SNAPSHOTS)
                .where(RUN_METRIC_SNAPSHOTS.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .and(RUN_METRIC_SNAPSHOTS.COMPUTATION_ID.eq(computationId.toString()))
                .orderBy(RUN_METRIC_SNAPSHOTS.COMPUTED_AT_MS.desc())
                .fetch(recordMapper::map);
    }

    @Override
    public Optional<UUID> findLatestComputationId(UUID runId) {
        return dsl.select(RUN_METRIC_SNAPSHOTS.COMPUTATION_ID)
                .from(RUN_METRIC_SNAPSHOTS)
                .where(RUN_METRIC_SNAPSHOTS.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .orderBy(RUN_METRIC_SNAPSHOTS.COMPUTED_AT_MS.desc())
                .limit(1)
                .fetchOptional(r -> UUID.fromString(r.getValue(RUN_METRIC_SNAPSHOTS.COMPUTATION_ID)));
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
