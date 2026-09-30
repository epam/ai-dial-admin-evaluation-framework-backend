package com.epam.aidial.evaluation.data.db.analytics.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseMetricScoreAggregated;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.records.TestCaseMetricScoresAggregatedRecord;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class TestCaseMetricScoreAggregatedRecordMapper {

    public TestCaseMetricScoreAggregated map(TestCaseMetricScoresAggregatedRecord r) {
        return TestCaseMetricScoreAggregated.builder()
                .id(UUID.fromString(r.getId()))
                .testSuiteRunId(UUID.fromString(r.getTestSuiteRunId()))
                .testCaseId(UUID.fromString(r.getTestCaseId()))
                .computationId(UUID.fromString(r.getComputationId()))
                .metricScores(toJsonString(r.getMetricScores()))
                .createdAtMs(r.getCreatedAtMs())
                .computedAtMs(r.getComputedAtMs())
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
