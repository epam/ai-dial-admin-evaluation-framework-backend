package com.epam.aidial.evaluation.data.db.analytics.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.records.TestCaseEvalSummariesRecord;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EvalSummaryRecordMapper")
class EvalSummaryRecordMapperTest {

    private final EvalSummaryRecordMapper mapper = new EvalSummaryRecordMapper();

    @Test
    @DisplayName("map() round-trips metricEvalDurationMs from a typed record")
    void map_roundTripsMetricEvalDurationMs() {
        TestCaseEvalSummariesRecord record = new TestCaseEvalSummariesRecord();
        record.setId(UUID.randomUUID().toString());
        record.setTestSuiteId(UUID.randomUUID().toString());
        record.setTestSuiteRunId(UUID.randomUUID().toString());
        record.setTestCaseRunResultId(UUID.randomUUID().toString());
        record.setTestCaseId(UUID.randomUUID().toString());
        record.setTestCaseName("tc1");
        record.setRunIndex(0);
        record.setRequestIndex(0);
        record.setTotalRequests(1);
        record.setTurnIndex(0);
        record.setTotalTurns(1);
        record.setComputationId(UUID.randomUUID().toString());
        record.setTestCaseData(JSONB.valueOf("{}"));
        record.setExtractedColumns(JSONB.valueOf("{}"));
        record.setExecutionStatus("SUCCESS");
        record.setExecDurationMs(1234L);
        record.setMetricEvalDurationMs(456L);
        record.setMetricValues(JSONB.valueOf("{}"));
        record.setExtractionWarnings(JSONB.valueOf("[]"));
        record.setCreatedAtMs(1000L);
        record.setComputedAtMs(2000L);

        EvalSummary entity = mapper.map(record);

        assertThat(entity.getExecDurationMs()).isEqualTo(1234L);
        assertThat(entity.getMetricEvalDurationMs()).isEqualTo(456L);
    }
}
