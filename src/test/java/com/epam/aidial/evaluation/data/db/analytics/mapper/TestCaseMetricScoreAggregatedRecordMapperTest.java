package com.epam.aidial.evaluation.data.db.analytics.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseMetricScoreAggregated;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.records.TestCaseMetricScoresAggregatedRecord;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TestCaseMetricScoreAggregatedRecordMapper")
class TestCaseMetricScoreAggregatedRecordMapperTest {

    private final TestCaseMetricScoreAggregatedRecordMapper mapper = new TestCaseMetricScoreAggregatedRecordMapper();

    @Test
    @DisplayName("map() round-trips all columns including the metricScores JSONB payload")
    void map_roundTripsAllColumns() {
        UUID id = UUID.randomUUID();
        UUID testSuiteRunId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        TestCaseMetricScoresAggregatedRecord record = new TestCaseMetricScoresAggregatedRecord();
        record.setId(id.toString());
        record.setTestSuiteRunId(testSuiteRunId.toString());
        record.setTestCaseId(testCaseId.toString());
        record.setComputationId(computationId.toString());
        record.setMetricScores(JSONB.valueOf("{\"Accuracy\":{\"avg\":0.4,\"min\":0.2,\"max\":0.5,\"count\":3}}"));
        record.setCreatedAtMs(1000L);
        record.setComputedAtMs(2000L);

        TestCaseMetricScoreAggregated entity = mapper.map(record);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getTestSuiteRunId()).isEqualTo(testSuiteRunId);
        assertThat(entity.getTestCaseId()).isEqualTo(testCaseId);
        assertThat(entity.getComputationId()).isEqualTo(computationId);
        assertThat(entity.getMetricScores())
                .isEqualTo("{\"Accuracy\":{\"avg\":0.4,\"min\":0.2,\"max\":0.5,\"count\":3}}");
        assertThat(entity.getCreatedAtMs()).isEqualTo(1000L);
        assertThat(entity.getComputedAtMs()).isEqualTo(2000L);
    }
}
