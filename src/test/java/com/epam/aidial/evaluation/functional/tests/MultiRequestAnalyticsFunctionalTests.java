package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-request result-row persistence: the widened natural key admitting one row per chain request, the
 * request columns round-tripping through write and read, and the import path taking {@code requestLabel}
 * verbatim with no snapshot cross-validation — which is what keeps external-run import working.
 */
@DisplayName("Multi-Request Analytics Functional Tests")
public abstract class MultiRequestAnalyticsFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        testSuiteId = metaTestDataHelper
                .createTestSuite("MR-Analytics-" + UUID.randomUUID())
                .getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("two chain requests of one test-case run both persist — request_index distinguishes them")
    void twoChainRequestsBothPersist() {
        UUID caseId = UUID.randomUUID();

        BatchWriteResponseDto response =
                write(List.of(chainItem(caseId, "case-1", 0, 0, "setup"), chainItem(caseId, "case-1", 0, 1, "invoke")));

        assertThat(response.getTotalItems()).isEqualTo(2);
        List<Map<String, Object>> rows = analyticsTestDataHelper.findResultsByRunId(testSuiteRunId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("request_index")).containsExactlyInAnyOrder(0, 1);
        assertThat(rows).extracting(row -> row.get("request_label")).containsExactlyInAnyOrder("setup", "invoke");
    }

    @Test
    @DisplayName("a three-request chain persists three rows sharing test case identity")
    void threeRequestChainSharesIdentity() {
        UUID caseId = UUID.randomUUID();

        write(List.of(
                chainItem(caseId, "case-1", 0, 0, "setup"),
                chainItem(caseId, "case-1", 0, 1, "configure"),
                chainItem(caseId, "case-1", 0, 2, "invoke")));

        List<Map<String, Object>> rows = analyticsTestDataHelper.findResultsByRunId(testSuiteRunId);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("test_case_id")).isEqualTo(caseId.toString()));
        assertThat(rows).extracting(row -> row.get("run_index")).containsOnly(0);
    }

    @Test
    @DisplayName("an omitted requestIndex defaults to 0 with a null label, preserving existing importers")
    void omittedRequestFieldsDefault() {
        write(List.of(plainItem(UUID.randomUUID(), "case-1", 0)));

        Map<String, Object> row =
                analyticsTestDataHelper.findResultsByRunId(testSuiteRunId).getFirst();
        assertThat(row.get("request_index")).isEqualTo(0);
        assertThat(row.get("request_label")).isNull();
    }

    @Test
    @DisplayName("a negative requestIndex is rejected with 400")
    void negativeRequestIndexRejected() {
        TestCaseRunResultItemDto item = plainItem(UUID.randomUUID(), "case-1", 0);
        item.setRequestIndex(-1);

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"),
                jsonEntity(BatchWriteRequestDto.builder()
                        .testSuiteId(testSuiteId)
                        .testSuiteRunId(testSuiteRunId)
                        .results(List.of(item))
                        .build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an external run's arbitrary labels and out-of-range indices are persisted verbatim")
    void externalRunLabelsPersistedVerbatim() {
        // The run has no chain configured at all; the import path must not bound the index or derive the
        // label from a snapshot, or results from EXTERNAL test suite runs could never be imported.
        UUID caseId = UUID.randomUUID();

        write(List.of(chainItem(caseId, "case-1", 0, 97, "external-stage-97")));

        Map<String, Object> row =
                analyticsTestDataHelper.findResultsByRunId(testSuiteRunId).getFirst();
        assertThat(row.get("request_index")).isEqualTo(97);
        assertThat(row.get("request_label")).isEqualTo("external-stage-97");
    }

    @Test
    @DisplayName("rows keep their per-request extracted columns, so a chain's rows are sparse by design")
    void extractedColumnsAreRequestLocal() {
        UUID caseId = UUID.randomUUID();
        TestCaseRunResultItemDto setup = chainItem(caseId, "case-1", 0, 0, "setup");
        ObjectNode setupColumns = JsonNodeFactory.instance.objectNode();
        setupColumns.put("session_id", "abc");
        setup.setExtractedColumns(setupColumns);

        TestCaseRunResultItemDto invoke = chainItem(caseId, "case-1", 0, 1, "invoke");
        ObjectNode invokeColumns = JsonNodeFactory.instance.objectNode();
        invokeColumns.put("answer", "42");
        invoke.setExtractedColumns(invokeColumns);

        write(List.of(setup, invoke));

        List<Map<String, Object>> rows = analyticsTestDataHelper.findResultsByRunId(testSuiteRunId);
        Map<String, Object> setupRow = rows.stream()
                .filter(row -> Integer.valueOf(0).equals(row.get("request_index")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> invokeRow = rows.stream()
                .filter(row -> Integer.valueOf(1).equals(row.get("request_index")))
                .findFirst()
                .orElseThrow();

        assertThat(String.valueOf(setupRow.get("extracted_columns")))
                .contains("session_id")
                .doesNotContain("answer");
        assertThat(String.valueOf(invokeRow.get("extracted_columns")))
                .contains("answer")
                .doesNotContain("session_id");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private BatchWriteResponseDto write(List<TestCaseRunResultItemDto> items) {
        var response = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"),
                jsonEntity(BatchWriteRequestDto.builder()
                        .testSuiteId(testSuiteId)
                        .testSuiteRunId(testSuiteRunId)
                        .results(items)
                        .build()),
                BatchWriteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private TestCaseRunResultItemDto chainItem(
            UUID caseId, String name, int runIndex, int requestIndex, String requestLabel) {
        TestCaseRunResultItemDto item = plainItem(caseId, name, runIndex);
        item.setRequestIndex(requestIndex);
        item.setRequestLabel(requestLabel);
        return item;
    }

    private TestCaseRunResultItemDto plainItem(UUID caseId, String name, int runIndex) {
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test prompt");

        return TestCaseRunResultItemDto.builder()
                .testCaseId(caseId)
                .testCaseName(name)
                .runIndex(runIndex)
                .testCaseData(testCaseData)
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401234L)
                        .traceId("trace-mr")
                        .build())
                .build();
    }
}
