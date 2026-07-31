package com.epam.aidial.evaluation.data.db.analytics.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.records.RunMetricSnapshotsRecord;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class RunMetricSnapshotRecordMapper {

    public RunMetricSnapshot map(RunMetricSnapshotsRecord r) {
        return RunMetricSnapshot.builder()
                .id(UUID.fromString(r.getId()))
                .computationId(UUID.fromString(r.getComputationId()))
                .testSuiteRunId(UUID.fromString(r.getTestSuiteRunId()))
                .tsmdId(UUID.fromString(r.getTsmdId()))
                .tsmdName(r.getTsmdName())
                .metricDeclarationId(UUID.fromString(r.getMetricDeclarationId()))
                .metricDeclarationVersionId(UUID.fromString(r.getMetricDeclarationVersionId()))
                .configBindings(toJsonString(r.getConfigBindings()))
                .inputBindings(toJsonString(r.getInputBindings()))
                .outputSchema(toJsonString(r.getOutputSchema()))
                .computedAtMs(r.getComputedAtMs())
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
