package com.epam.aidial.evaluation.data.db.analytics.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.records.MetricScoreResultRecord;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class MetricScoreResultRecordMapper {

    public MetricScoreResult map(MetricScoreResultRecord r) {
        return MetricScoreResult.builder()
                .id(UUID.fromString(r.getId()))
                .testSuiteRunId(UUID.fromString(r.getTestSuiteRunId()))
                .testSuiteId(UUID.fromString(r.getTestSuiteId()))
                .computationId(UUID.fromString(r.getComputationId()))
                .metricScoreName(r.getMetricScoreName())
                .metricName(r.getMetricName())
                .value(r.getValue())
                .computedAtMs(r.getComputedAtMs())
                .build();
    }
}
