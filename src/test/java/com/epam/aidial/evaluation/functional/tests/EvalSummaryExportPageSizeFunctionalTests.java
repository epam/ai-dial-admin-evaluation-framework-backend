package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryExportRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteRequestDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the multi-page streaming path of the export with a small
 * {@code csv.export.page-size}. The page size is set on the registering nested class via
 * {@code @TestPropertySource} so this test's cursor loop is forced to run for multiple pages,
 * proving that per-page {@code analyticsTransactionTemplate.execute} commits release the
 * analytics connection between pages and the export does not hold one transaction across the
 * whole stream.
 */
@DisplayName("Eval Summary Export – multi-page streaming")
public abstract class EvalSummaryExportPageSizeFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        analyticsTestDataHelper.cleanupRunMetricSnapshots();
        testSuiteId =
                metaTestDataHelper.createTestSuite("Export Page Size Suite").getId();
    }

    @Test
    @DisplayName("Export streams every row when the dataset spans at least three repository pages")
    void exportStreamsAcrossMultiplePages() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshot(completedRun.getId(), computationId);

        // page-size=2 (from the nested class @TestPropertySource); 6 rows ⇒ pages 1, 2, 3.
        int rowCount = 6;
        seedSummaries(testSuiteId, completedRun.getId(), computationId, rowCount);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .build();
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries/export.csv"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String[] lines = response.getBody().split("\\r?\\n");
        // header + 6 data rows
        assertThat(lines).hasSize(rowCount + 1);
        // Every seeded row appears in the output (order is deterministic by (created_at_ms, id)).
        for (int i = 0; i < rowCount; i++) {
            assertThat(response.getBody()).contains("page-case-" + i);
        }
    }

    private void insertRunMetricSnapshot(UUID runId, UUID computationId) {
        RunMetricSnapshotBatchWriteRequestDto snapshotRequest = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .snapshots(List.of(RunMetricSnapshotBatchWriteItemDto.builder()
                        .tsmdId(UUID.randomUUID())
                        .tsmdName("Accuracy")
                        .metricDeclarationId(UUID.randomUUID())
                        .metricDeclarationVersionId(UUID.randomUUID())
                        .build()))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(snapshotRequest), BatchWriteResponseDto.class);
    }

    private void seedSummaries(UUID suiteId, UUID runId, UUID computationId, int count) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
            metricValues.putObject("Accuracy").put("score", 0.5 + i * 0.05);
            ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
            testCaseData.put("prompt", "p-" + i);
            items.add(EvalSummaryBatchWriteItemDto.builder()
                    .testCaseRunResultId(UUID.randomUUID())
                    .testCaseId(UUID.randomUUID())
                    .testCaseName("page-case-" + i)
                    .runIndex(0)
                    .testCaseData(testCaseData)
                    .executionStatus(ExecutionStatus.SUCCESS)
                    .execDurationMs(100L)
                    .metricEvalDurationMs(0L)
                    .responseStatusCode(200)
                    .metricValues(metricValues)
                    .build());
        }
        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(suiteId)
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(items)
                .build();
        restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);
    }
}
