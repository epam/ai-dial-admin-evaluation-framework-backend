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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Eval Summary Export Functional Tests")
public abstract class EvalSummaryExportFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        analyticsTestDataHelper.cleanupRunMetricSnapshots();
        testSuiteId = metaTestDataHelper.createTestSuite("Export Suite").getId();
    }

    @Test
    @DisplayName("Export rejects a RUNNING run with HTTP 409 and RUN_NOT_TERMINAL")
    void exportRejectsRunningRunWith409() {
        TestSuiteRun runningRun = metaTestDataHelper.createRunningRun(testSuiteId, "running-export");

        EvalSummaryExportRequestDto request =
                EvalSummaryExportRequestDto.builder().runId(runningRun.getId()).build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("RUN_NOT_TERMINAL");
        assertThat(response.getBody()).contains("RUNNING");
    }

    @Test
    @DisplayName("Export rejects a PENDING run with HTTP 409 and RUN_NOT_TERMINAL")
    void exportRejectsPendingRunWith409() {
        TestSuiteRun pendingRun = metaTestDataHelper.createPendingRun(testSuiteId, "pending-export");

        EvalSummaryExportRequestDto request =
                EvalSummaryExportRequestDto.builder().runId(pendingRun.getId()).build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("RUN_NOT_TERMINAL");
        assertThat(response.getBody()).contains("PENDING");
    }

    @Test
    @DisplayName("Preview rejects a RUNNING run with HTTP 409 and RUN_NOT_TERMINAL")
    void previewRejectsRunningRunWith409() {
        TestSuiteRun runningRun = metaTestDataHelper.createRunningRun(testSuiteId, "running-preview");

        ResponseEntity<String> response =
                restTemplate.exchange(previewUrl(runningRun.getId(), null), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("RUN_NOT_TERMINAL");
    }

    @Test
    @DisplayName("Export of a COMPLETED run with at least one computation returns 200 and a CSV body")
    void exportCompletedRunReturns200() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);
        insertEvalSummaries(testSuiteId, completedRun.getId(), computationId, 2);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getBody()).isNotBlank();
        // First line must be the header row including the always-emitted identity columns.
        String firstLine = response.getBody().split("\\r?\\n", 2)[0];
        assertThat(firstLine).contains("testCaseName", "executionStatus");
    }

    @Test
    @DisplayName("Export with an unknown column name returns HTTP 400 listing the offending column")
    void exportUnknownColumnReturns400() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);
        insertEvalSummaries(testSuiteId, completedRun.getId(), computationId, 2);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .columns(List.of("testCaseName", "thisColumnDoesNotExist"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("thisColumnDoesNotExist");
    }

    @Test
    @DisplayName("Export with a well-formed but unknown computation UUID returns HTTP 404")
    void exportUnknownComputationUuidReturns404() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .computation(UUID.randomUUID().toString())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Export with computation=latest when no eval summaries exist returns HTTP 404")
    void exportLatestWithNoSnapshotsReturns404() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .computation("latest")
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Export with a malformed computation string (non-UUID, non-'latest') returns HTTP 400")
    void exportMalformedComputationReturns400() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .computation("not-a-uuid")
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Export with an out-of-whitelist filter token returns HTTP 400")
    void exportFilterOutOfWhitelistReturns400() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);
        insertEvalSummaries(testSuiteId, completedRun.getId(), computationId, 2);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .filter(List.of("noSuchField:eq:x"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Export with a valid in-whitelist filter token returns HTTP 200")
    void exportValidFilterReturns200() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);
        insertEvalSummaries(testSuiteId, completedRun.getId(), computationId, 2);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .filter(List.of("executionStatus:eq:SUCCESS"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Header line + at least the two data rows.
        String[] lines = response.getBody().split("\\r?\\n");
        assertThat(lines.length).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Preview rejects an out-of-whitelist filter token with HTTP 400")
    void previewFilterOutOfWhitelistReturns400() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);
        insertEvalSummaries(testSuiteId, completedRun.getId(), computationId, 2);

        ResponseEntity<String> response = restTemplate.exchange(
                previewUrl(completedRun.getId(), null) + "&filter=noSuchField:eq:x",
                HttpMethod.GET,
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Preview rejects a malformed (non-UUID) runId query param with HTTP 400")
    void previewMalformedRunIdReturns400() {
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries/export/preview") + "?runId=meh", HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
        assertThat(response.getBody()).contains("runId");
    }

    @Test
    @DisplayName("Run-scoping invariant: two runs with identical created_at_ms export disjoint row sets")
    void exportRunScopingInvariantWithSameCreatedAtMs() {
        long sharedCreatedAtMs = 1_700_000_000_000L;

        TestSuiteRun runA = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        TestSuiteRun runB = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        metaTestDataHelper.forceRunCreatedAt(runA.getId(), sharedCreatedAtMs);
        metaTestDataHelper.forceRunCreatedAt(runB.getId(), sharedCreatedAtMs);

        UUID computationA = UUID.randomUUID();
        UUID computationB = UUID.randomUUID();
        insertRunMetricSnapshots(runA.getId(), computationA);
        insertRunMetricSnapshots(runB.getId(), computationB);
        // Names use a 'zzz' prefix so they cannot accidentally substring-match hex characters
        // in randomly-generated UUIDs elsewhere in the rendered CSV.
        insertNamedEvalSummaries(
                testSuiteId, runA.getId(), computationA, List.of("zzzAlpha1", "zzzAlpha2", "zzzAlpha3"));
        insertNamedEvalSummaries(testSuiteId, runB.getId(), computationB, List.of("zzzBeta1", "zzzBeta2"));

        // Export run A — must contain only run A's rows even though run B shares the same created_at_ms.
        EvalSummaryExportRequestDto requestA = EvalSummaryExportRequestDto.builder()
                .runId(runA.getId())
                .computation(computationA.toString())
                .build();
        ResponseEntity<String> responseA = restTemplate.postForEntity(exportUrl(), jsonEntity(requestA), String.class);
        assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseA.getBody())
                .contains("zzzAlpha1", "zzzAlpha2", "zzzAlpha3")
                .doesNotContain("zzzBeta1", "zzzBeta2");

        // Export run B — must contain only run B's rows.
        EvalSummaryExportRequestDto requestB = EvalSummaryExportRequestDto.builder()
                .runId(runB.getId())
                .computation(computationB.toString())
                .build();
        ResponseEntity<String> responseB = restTemplate.postForEntity(exportUrl(), jsonEntity(requestB), String.class);
        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseB.getBody())
                .contains("zzzBeta1", "zzzBeta2")
                .doesNotContain("zzzAlpha1", "zzzAlpha2", "zzzAlpha3");
    }

    @Test
    @DisplayName(
            "Default export omits requestBody/responseBody; explicit columns including bodies populate them via the JOIN")
    void exportBodiesViaExplicitColumns() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);

        // Seed a test_case_run_result row carrying request/response bodies and an eval summary
        // pointing at it via test_case_run_result_id so the LEFT JOIN populates body cells.
        UUID testCaseId = UUID.randomUUID();
        UUID runResultId = analyticsTestDataHelper.createTestRunResult(
                completedRun.getId(),
                testSuiteId,
                testCaseId,
                "case-bodies",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                "{\"choices\":[{\"message\":{\"content\":\"world\"}}]}",
                System.currentTimeMillis());

        EvalSummaryBatchWriteItemDto item = buildBasicSummaryItem(testCaseId, "case-bodies");
        item.setTestCaseRunResultId(runResultId);
        EvalSummaryBatchWriteRequestDto writeRequest = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(completedRun.getId())
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item))
                .build();
        restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(writeRequest), String.class);

        // (a) Explicit columns including both body columns — must populate cells from the JOIN and
        // emit ONLY the requested columns in the requested order.
        EvalSummaryExportRequestDto withBodies = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .columns(List.of("testCaseName", "runIndex", "requestBody", "responseBody"))
                .build();
        ResponseEntity<String> bodiesResponse =
                restTemplate.postForEntity(exportUrl(), jsonEntity(withBodies), String.class);

        assertThat(bodiesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String[] lines = bodiesResponse.getBody().split("\\r?\\n");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(lines[0]).isEqualTo("testCaseName,runIndex,requestBody,responseBody");
        assertThat(bodiesResponse.getBody()).contains("hello");
        assertThat(bodiesResponse.getBody()).contains("world");

        // (b) Default (omitted columns) — header line must NOT include either body column.
        EvalSummaryExportRequestDto defaultRequest = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .build();
        ResponseEntity<String> defaultResponse =
                restTemplate.postForEntity(exportUrl(), jsonEntity(defaultRequest), String.class);
        String defaultHeader = defaultResponse.getBody().split("\\r?\\n", 2)[0];
        assertThat(defaultHeader).doesNotContain("requestBody", "responseBody");
    }

    @Test
    @DisplayName("Subset preserves user order; subset including responseBody returns 200 (JOIN projection picked)")
    void exportSubsetPreservesOrderAndResponseBodyForcesJoin() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);
        insertNamedEvalSummaries(testSuiteId, completedRun.getId(), computationId, List.of("case-subset"));

        // Non-trivial order: executionStatus before runIndex before testCaseName.
        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .columns(List.of("executionStatus", "runIndex", "testCaseName", "responseBody"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String header = response.getBody().split("\\r?\\n", 2)[0];
        assertThat(header).isEqualTo("executionStatus,runIndex,testCaseName,responseBody");
    }

    @Test
    @DisplayName(
            "Preview returns JSON without Content-Disposition; manifest uses new column shape; data rows preserve typed cells")
    void previewReturnsTypedJsonShape() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        ObjectNode outputSchema = JsonNodeFactory.instance.objectNode();
        outputSchema.putObject("properties").putObject("score").put("type", "number");
        insertRunMetricSnapshotsWithOutputSchema(completedRun.getId(), computationId, outputSchema);

        // Seed two summaries:
        //   firstItem  — metricInfos.Accuracy has NO key matching the schema field "score",
        //                so the payload routes to metricError::Accuracy (wholesale-error path).
        //   secondItem — Accuracy.score is JSON null, executionStatus FAILED.
        EvalSummaryBatchWriteItemDto firstItem = buildBasicSummaryItem(UUID.randomUUID(), "preview-A");
        ObjectNode metricInfos = JsonNodeFactory.instance.objectNode();
        metricInfos.putObject("Accuracy").put("version", "1.0").put("modelVersion", "gpt-4");
        firstItem.setMetricInfos(metricInfos);

        EvalSummaryBatchWriteItemDto secondItem = buildBasicSummaryItem(UUID.randomUUID(), "preview-B");
        ObjectNode nulledMetricValues = JsonNodeFactory.instance.objectNode();
        nulledMetricValues.putObject("Accuracy").putNull("score");
        secondItem.setMetricValues(nulledMetricValues);
        secondItem.setExecutionStatus(ExecutionStatus.FAILED);

        EvalSummaryBatchWriteRequestDto writeRequest = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(completedRun.getId())
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(firstItem, secondItem))
                .build();
        restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(writeRequest), String.class);

        ResponseEntity<List<List<Object>>> response = restTemplate.exchange(
                previewUrl(completedRun.getId(), computationId.toString()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<List<Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).isNull();

        List<List<Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isBetween(2, 11); // headers + ≤10 data rows
        List<Object> headers = body.get(0);
        assertThat(headers)
                .contains(
                        "requestBody",
                        "responseBody",
                        "extractionWarnings",
                        "metric::Accuracy::score",
                        "metricInfo::Accuracy::score",
                        "metricError::Accuracy");
        assertThat(headers).doesNotContain("metricInfos");

        int accuracyScoreIdx = headers.indexOf("metric::Accuracy::score");
        int accuracyInfoIdx = headers.indexOf("metricInfo::Accuracy::score");
        int accuracyErrorIdx = headers.indexOf("metricError::Accuracy");

        boolean foundWholesaleErrorObject = false;
        boolean foundNumericScore = false;
        boolean foundNullScore = false;
        for (int i = 1; i < body.size(); i++) {
            Object errorCell = body.get(i).get(accuracyErrorIdx);
            if (errorCell instanceof Map<?, ?> map
                    && "1.0".equals(map.get("version"))
                    && "gpt-4".equals(map.get("modelVersion"))) {
                foundWholesaleErrorObject = true;
            }
            // The per-field info column is always null in this scenario: firstItem's payload
            // is wholesale-error material; secondItem has no metricInfos at all.
            assertThat(body.get(i).get(accuracyInfoIdx))
                    .as("metricInfo::Accuracy::score should be null in this scenario")
                    .isNull();
            Object scoreCell = body.get(i).get(accuracyScoreIdx);
            if (scoreCell instanceof Number) {
                foundNumericScore = true;
            } else if (scoreCell == null) {
                foundNullScore = true;
            }
        }
        assertThat(foundWholesaleErrorObject)
                .as("preview-A's wholesale-error payload should route to metricError::Accuracy as a JSON object")
                .isTrue();
        assertThat(foundNumericScore)
                .as("at least one preview row should carry metric::Accuracy::score as a JSON number")
                .isTrue();
        assertThat(foundNullScore)
                .as("at least one preview row should carry metric::Accuracy::score as JSON null")
                .isTrue();
    }

    @Test
    @DisplayName("Default export of a rich-schema run emits the full ordered manifest, two run-index rows, "
            + "inlined data:* / response:* cells and metric:/metricInfo:/metricError: cells; bodies are absent")
    void exportRichSchemaDefaultShape() {
        // Rich schema: one STRING and one FILE testCaseSchema field; one STRING and one FILE
        // responseColumn; two metrics with two output fields each.
        String testCaseSchemaJson =
                "[{\"name\":\"prompt\",\"type\":\"STRING\"}," + "{\"name\":\"attachment\",\"type\":\"FILE\"}]";
        String responseColumnsJson = "[{\"name\":\"answer\",\"expression\":\"$.a\",\"type\":\"STRING\"},"
                + "{\"name\":\"audio\",\"expression\":\"$.audio\",\"type\":\"FILE\"}]";
        metaTestDataHelper.updateSuiteSchema(testSuiteId, testCaseSchemaJson, responseColumnsJson);

        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();

        ObjectNode accuracySchema = JsonNodeFactory.instance.objectNode();
        ObjectNode accuracyProps = accuracySchema.putObject("properties");
        accuracyProps.putObject("score").put("type", "number");
        accuracyProps.putObject("confidence").put("type", "number");
        ObjectNode relevanceSchema = JsonNodeFactory.instance.objectNode();
        ObjectNode relevanceProps = relevanceSchema.putObject("properties");
        relevanceProps.putObject("score").put("type", "number");
        relevanceProps.putObject("explanation").put("type", "number");
        insertTwoRunMetricSnapshots(
                completedRun.getId(), computationId, "Accuracy", accuracySchema, "Relevance", relevanceSchema);

        // Seed two summaries: runIndex 0 and 1, both with populated data.*, response.*, metric values.
        insertRichSummaries(testSuiteId, completedRun.getId(), computationId);

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .build();
        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String[] lines = response.getBody().split("\\r?\\n");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3); // header + 2 data rows

        String header = lines[0];
        // Identity / timestamp / execution columns precede inlined data / response / metric columns.
        assertThat(header)
                .contains(
                        "id",
                        "testSuiteId",
                        "testSuiteRunId",
                        "testCaseId",
                        "testCaseName",
                        "runIndex",
                        "createdAt",
                        "computedAt",
                        "executionStatus",
                        "execDurationMs",
                        "responseStatusCode",
                        "data::prompt",
                        "data::attachment",
                        "response::answer",
                        "response::audio",
                        "metric::Accuracy::score",
                        "metric::Accuracy::confidence",
                        "metricInfo::Accuracy::score",
                        "metricInfo::Accuracy::confidence",
                        "metricError::Accuracy",
                        "metric::Relevance::score",
                        "metric::Relevance::explanation",
                        "metricInfo::Relevance::score",
                        "metricInfo::Relevance::explanation",
                        "metricError::Relevance",
                        "extractionWarnings");
        // Legacy single metricInfos JSON-blob column is gone; default export must omit body columns.
        assertThat(header).doesNotContain("requestBody", "responseBody");
        assertThat(header.split(",")).doesNotContain("metricInfos");

        // The two data rows carry both runIndex 0 and 1 plus populated cell values.
        String body = response.getBody();
        assertThat(body).contains("Hi there");
        assertThat(body).contains("@ef/files/answer.png");
        assertThat(body).contains("@ef/files/audio.mp3");
    }

    @Test
    @DisplayName(
            "Per-field info routing: success-with-details, per-field error envelope, and wholesale error each land in the correct cells")
    void exportRoutesPerFieldDetailsAndWholesaleErrors() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();

        ObjectNode retrievalSchema = JsonNodeFactory.instance.objectNode();
        ObjectNode props = retrievalSchema.putObject("properties");
        props.putObject("recall").put("type", "number");
        props.putObject("precision").put("type", "number");
        insertRunMetricSnapshotsForNamedMetric(completedRun.getId(), computationId, "Retrieval", retrievalSchema);

        EvalSummaryBatchWriteItemDto perFieldSuccess = buildBasicSummaryItem(UUID.randomUUID(), "row-success");
        ObjectNode metricValuesSuccess = JsonNodeFactory.instance.objectNode();
        metricValuesSuccess.putObject("Retrieval").put("recall", 0.7).put("precision", 0.8);
        perFieldSuccess.setMetricValues(metricValuesSuccess);
        ObjectNode metricInfosSuccess = JsonNodeFactory.instance.objectNode();
        ObjectNode retrievalSuccess = metricInfosSuccess.putObject("Retrieval");
        retrievalSuccess.putObject("recall").putObject("details").put("reason", "exact-match");
        retrievalSuccess.putObject("precision").putObject("details").put("reason", "exact-match");
        perFieldSuccess.setMetricInfos(metricInfosSuccess);

        EvalSummaryBatchWriteItemDto perFieldError = buildBasicSummaryItem(UUID.randomUUID(), "row-field-error");
        ObjectNode metricValuesPartial = JsonNodeFactory.instance.objectNode();
        ObjectNode retrievalPartialValues = metricValuesPartial.putObject("Retrieval");
        retrievalPartialValues.putNull("recall");
        retrievalPartialValues.put("precision", 0.65);
        perFieldError.setMetricValues(metricValuesPartial);
        ObjectNode metricInfosPartial = JsonNodeFactory.instance.objectNode();
        ObjectNode retrievalPartialInfos = metricInfosPartial.putObject("Retrieval");
        retrievalPartialInfos.putObject("recall").put("type", "error").put("message", "facts missing");
        retrievalPartialInfos.putObject("precision").putObject("details").put("reason", "ok");
        perFieldError.setMetricInfos(metricInfosPartial);

        EvalSummaryBatchWriteItemDto wholesaleError = buildBasicSummaryItem(UUID.randomUUID(), "row-wholesale-error");
        wholesaleError.setExecutionStatus(ExecutionStatus.FAILED);
        ObjectNode metricInfosWholesale = JsonNodeFactory.instance.objectNode();
        metricInfosWholesale
                .putObject("Retrieval")
                .put("type", "error")
                .put("message", "metric crashed before evaluation");
        wholesaleError.setMetricInfos(metricInfosWholesale);

        EvalSummaryBatchWriteRequestDto writeRequest = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(completedRun.getId())
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(perFieldSuccess, perFieldError, wholesaleError))
                .build();
        restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(writeRequest), String.class);

        // (a) CSV path — header contains all six metric-block columns and no legacy blob.
        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .columns(List.of(
                        "testCaseName",
                        "metric::Retrieval::recall",
                        "metric::Retrieval::precision",
                        "metricInfo::Retrieval::recall",
                        "metricInfo::Retrieval::precision",
                        "metricError::Retrieval"))
                .build();
        ResponseEntity<String> csvResponse = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(csvResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String csvBody = csvResponse.getBody();
        String[] csvLines = csvBody.split("\\r?\\n");
        assertThat(csvLines[0])
                .isEqualTo("testCaseName,metric::Retrieval::recall,metric::Retrieval::precision,"
                        + "metricInfo::Retrieval::recall,metricInfo::Retrieval::precision,"
                        + "metricError::Retrieval");
        // The wholesale-error payload's distinctive marker must land in metricError::Retrieval, not in
        // the per-field info cells.
        assertThat(csvBody).contains("metric crashed before evaluation");
        assertThat(csvBody).contains("facts missing");
        assertThat(csvBody).contains("exact-match");

        // (b) Preview path — typed cells let us verify routing per row precisely.
        ResponseEntity<List<List<Object>>> previewResponse = restTemplate.exchange(
                previewUrl(completedRun.getId(), computationId.toString()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<List<Object>>>() {});
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<List<Object>> previewBody = previewResponse.getBody();
        assertThat(previewBody).isNotNull();

        List<Object> previewHeaders = previewBody.get(0);
        int nameIdx = previewHeaders.indexOf("testCaseName");
        int recallInfoIdx = previewHeaders.indexOf("metricInfo::Retrieval::recall");
        int precisionInfoIdx = previewHeaders.indexOf("metricInfo::Retrieval::precision");
        int errorIdx = previewHeaders.indexOf("metricError::Retrieval");
        assertThat(recallInfoIdx).isPositive();
        assertThat(precisionInfoIdx).isPositive();
        assertThat(errorIdx).isPositive();

        // Match by testCaseName to avoid coupling to row order.
        boolean checkedSuccess = false;
        boolean checkedFieldError = false;
        boolean checkedWholesale = false;
        for (int i = 1; i < previewBody.size(); i++) {
            List<Object> row = previewBody.get(i);
            Object name = row.get(nameIdx);
            if ("row-success".equals(name)) {
                assertThat(row.get(recallInfoIdx)).isInstanceOf(Map.class);
                assertThat(row.get(precisionInfoIdx)).isInstanceOf(Map.class);
                assertThat(row.get(errorIdx)).isNull();
                checkedSuccess = true;
            } else if ("row-field-error".equals(name)) {
                Object recallInfoCell = row.get(recallInfoIdx);
                assertThat(recallInfoCell).isInstanceOf(Map.class);
                assertThat(((Map<?, ?>) recallInfoCell).get("type")).isEqualTo("error");
                assertThat(((Map<?, ?>) recallInfoCell).get("message")).isEqualTo("facts missing");
                assertThat(row.get(precisionInfoIdx)).isInstanceOf(Map.class);
                assertThat(row.get(errorIdx)).isNull();
                checkedFieldError = true;
            } else if ("row-wholesale-error".equals(name)) {
                assertThat(row.get(recallInfoIdx)).isNull();
                assertThat(row.get(precisionInfoIdx)).isNull();
                Object errorCell = row.get(errorIdx);
                assertThat(errorCell).isInstanceOf(Map.class);
                assertThat(((Map<?, ?>) errorCell).get("type")).isEqualTo("error");
                assertThat(((Map<?, ?>) errorCell).get("message")).isEqualTo("metric crashed before evaluation");
                checkedWholesale = true;
            }
        }
        assertThat(checkedSuccess).as("row-success preview row was asserted").isTrue();
        assertThat(checkedFieldError)
                .as("row-field-error preview row was asserted")
                .isTrue();
        assertThat(checkedWholesale)
                .as("row-wholesale-error preview row was asserted")
                .isTrue();
    }

    @Test
    @DisplayName("Legacy run (suite_snapshot null) returns HTTP 422 and SNAPSHOT_SUITE_MISSING")
    void exportLegacyRunReturns422() {
        TestSuiteRun legacyRun = metaTestDataHelper.createLegacyTestSuiteRun(testSuiteId);

        EvalSummaryExportRequestDto request =
                EvalSummaryExportRequestDto.builder().runId(legacyRun.getId()).build();
        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("SNAPSHOT_SUITE_MISSING");
    }

    @Test
    @DisplayName("Preview of a legacy run (suite_snapshot null) returns HTTP 422 and SNAPSHOT_SUITE_MISSING")
    void previewLegacyRunReturns422() {
        TestSuiteRun legacyRun = metaTestDataHelper.createLegacyTestSuiteRun(testSuiteId);

        ResponseEntity<String> response =
                restTemplate.exchange(previewUrl(legacyRun.getId(), null), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("SNAPSHOT_SUITE_MISSING");
    }

    @Test
    @DisplayName("Run with an unsupported snapshotVersion returns HTTP 422 and UNSUPPORTED_SNAPSHOT_VERSION")
    void exportUnsupportedSnapshotVersionReturns422() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);

        // Persist a snapshot with a deliberately unsupported version.
        metaTestDataHelper.setRunSuiteSnapshot(
                completedRun.getId(), "{\"snapshotVersion\":\"999\",\"suiteType\":\"DEPLOYMENT\"}");

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .build();
        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("UNSUPPORTED_SNAPSHOT_VERSION");
    }

    @Test
    @DisplayName("Metric-free run: export and preview emit a metric-free manifest, 200 for its computation, "
            + "404 for an unknown one, and 400 for an explicit metric column")
    void metricFreeRunExportsWithoutMetricColumns() {
        String testCaseSchemaJson = "[{\"name\":\"prompt\",\"type\":\"STRING\"}]";
        String responseColumnsJson = "[{\"name\":\"answer\",\"expression\":\"$.a\",\"type\":\"STRING\"}]";
        metaTestDataHelper.updateSuiteSchema(testSuiteId, testCaseSchemaJson, responseColumnsJson);

        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertMetricLessEvalSummaries(completedRun.getId(), computationId);

        // (a) Default export: identity + data:: + response:: columns, and no metric column family.
        EvalSummaryExportRequestDto defaultRequest = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .build();
        ResponseEntity<String> defaultResponse =
                restTemplate.postForEntity(exportUrl(), jsonEntity(defaultRequest), String.class);

        assertThat(defaultResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String header = defaultResponse.getBody().split("\\r?\\n", 2)[0];
        assertThat(header).contains("testCaseName", "executionStatus", "data::prompt", "response::answer");
        assertThat(header).doesNotContain("metric::", "metricInfo::", "metricError::");

        // (b) Explicit computation of a metric-free run is found (previously 404 on empty snapshots).
        EvalSummaryExportRequestDto explicitRequest = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .computation(computationId.toString())
                .build();
        assertThat(restTemplate
                        .postForEntity(exportUrl(), jsonEntity(explicitRequest), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // (c) A computation that produced nothing is still not found.
        EvalSummaryExportRequestDto unknownRequest = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .computation(UUID.randomUUID().toString())
                .build();
        assertThat(restTemplate
                        .postForEntity(exportUrl(), jsonEntity(unknownRequest), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // (d) Preview returns the full manifest — including the body columns the default CSV omits —
        // and still no metric columns.
        ResponseEntity<List<List<Object>>> previewResponse = restTemplate.exchange(
                previewUrl(completedRun.getId(), null),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<List<Object>>>() {});

        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Object> previewHeaders = previewResponse.getBody().get(0);
        assertThat(previewHeaders).contains("data::prompt", "response::answer", "requestBody", "responseBody");
        assertThat(previewHeaders)
                .noneSatisfy(name -> assertThat((String) name)
                        .satisfiesAnyOf(
                                value -> assertThat(value).startsWith("metric::"),
                                value -> assertThat(value).startsWith("metricInfo::"),
                                value -> assertThat(value).startsWith("metricError::")));

        // (e) An explicit metric column is unknown against a metric-free manifest.
        EvalSummaryExportRequestDto metricColumnRequest = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .columns(List.of("metric::Accuracy::score"))
                .build();
        ResponseEntity<String> metricColumnResponse =
                restTemplate.postForEntity(exportUrl(), jsonEntity(metricColumnRequest), String.class);

        assertThat(metricColumnResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(metricColumnResponse.getBody()).contains("VALIDATION_ERROR", "metric::Accuracy::score");
    }

    @Test
    @DisplayName("Chained multi-turn run: CSV and preview headers carry runIndex, requestIndex, turnIndex "
            + "as three consecutive columns, with distinct cell values per row; a legacy single-request "
            + "single-turn run yields 0/0")
    void exportChainedMultiTurnRunCarriesRequestAndTurnIndices() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);

        // Simulates a 2-request chain whose second request is multi-turn with 2 turns: rows
        // (requestIndex, turnIndex) = (0, 0), (1, 0), (1, 1) — per design D16/R1 scenario.
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        items.add(chainRow("chain-case", 0, 0));
        items.add(chainRow("chain-case", 1, 0));
        items.add(chainRow("chain-case", 1, 1));
        EvalSummaryBatchWriteRequestDto writeRequest = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(completedRun.getId())
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(items)
                .build();
        restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(writeRequest), String.class);

        // (a) CSV — explicit subset isolates the three identity columns for an exact row-set match.
        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .columns(List.of("runIndex", "requestIndex", "turnIndex"))
                .build();
        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String[] lines = response.getBody().split("\\r?\\n");
        assertThat(lines[0]).isEqualTo("runIndex,requestIndex,turnIndex");
        assertThat(List.of(lines).subList(1, lines.length)).containsExactlyInAnyOrder("0,0,0", "0,1,0", "0,1,1");

        // (b) Default CSV header — runIndex, requestIndex, turnIndex are three consecutive columns.
        EvalSummaryExportRequestDto defaultRequest = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .build();
        ResponseEntity<String> defaultResponse =
                restTemplate.postForEntity(exportUrl(), jsonEntity(defaultRequest), String.class);
        List<String> defaultHeaderCols =
                List.of(defaultResponse.getBody().split("\\r?\\n", 2)[0].split(","));
        int runIndexPos = defaultHeaderCols.indexOf("runIndex");
        assertThat(defaultHeaderCols.get(runIndexPos + 1)).isEqualTo("requestIndex");
        assertThat(defaultHeaderCols.get(runIndexPos + 2)).isEqualTo("turnIndex");

        // (c) Preview — same three consecutive columns, typed cells.
        ResponseEntity<List<List<Object>>> previewResponse = restTemplate.exchange(
                previewUrl(completedRun.getId(), computationId.toString()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<List<Object>>>() {});
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Object> previewHeaders = previewResponse.getBody().get(0);
        int previewRunIndexPos = previewHeaders.indexOf("runIndex");
        assertThat(previewHeaders.get(previewRunIndexPos + 1)).isEqualTo("requestIndex");
        assertThat(previewHeaders.get(previewRunIndexPos + 2)).isEqualTo("turnIndex");
    }

    @Test
    @DisplayName("A legacy single-request single-turn run's rows carry requestIndex 0 and turnIndex 0")
    void exportSingleRequestRunYieldsZeroForBothIndices() {
        TestSuiteRun completedRun = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(completedRun.getId(), computationId);
        insertNamedEvalSummaries(testSuiteId, completedRun.getId(), computationId, List.of("legacy-case"));

        EvalSummaryExportRequestDto request = EvalSummaryExportRequestDto.builder()
                .runId(completedRun.getId())
                .columns(List.of("requestIndex", "turnIndex"))
                .build();
        ResponseEntity<String> response = restTemplate.postForEntity(exportUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String[] lines = response.getBody().split("\\r?\\n");
        assertThat(lines[1]).isEqualTo("0,0");
    }

    private EvalSummaryBatchWriteItemDto chainRow(String name, int requestIndex, int turnIndex) {
        ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
        metricValues.putObject("Accuracy").put("score", 0.9);
        return EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName(name)
                .runIndex(0)
                .requestIndex(requestIndex)
                .turnIndex(turnIndex)
                .testCaseData(JsonNodeFactory.instance.objectNode())
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(100L)
                .responseStatusCode(200)
                .metricValues(metricValues)
                .build();
    }

    private void insertMetricLessEvalSummaries(UUID runId, UUID computationId) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            EvalSummaryBatchWriteItemDto item = buildBasicSummaryItem(UUID.randomUUID(), "metric-free-" + i);
            item.setMetricValues(JsonNodeFactory.instance.objectNode());
            ObjectNode extractedColumns = JsonNodeFactory.instance.objectNode();
            extractedColumns.put("answer", "answer-" + i);
            item.setExtractedColumns(extractedColumns);
            items.add(item);
        }
        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(items)
                .build();
        restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);
    }

    private String exportUrl() {
        return apiUrl("/analytics/eval-summaries/export.csv");
    }

    private String previewUrl(UUID runId, String computation) {
        StringBuilder sb = new StringBuilder(apiUrl("/analytics/eval-summaries/export/preview"))
                .append("?runId=")
                .append(runId);
        if (computation != null) {
            sb.append("&computation=").append(computation);
        }
        return sb.toString();
    }

    private void insertRunMetricSnapshots(UUID runId, UUID computationId) {
        insertRunMetricSnapshotsWithOutputSchema(runId, computationId, null);
    }

    private void insertRunMetricSnapshotsWithOutputSchema(UUID runId, UUID computationId, JsonNode outputSchema) {
        insertRunMetricSnapshotsForNamedMetric(runId, computationId, "Accuracy", outputSchema);
    }

    private void insertRunMetricSnapshotsForNamedMetric(
            UUID runId, UUID computationId, String metricName, JsonNode outputSchema) {
        RunMetricSnapshotBatchWriteItemDto.RunMetricSnapshotBatchWriteItemDtoBuilder itemBuilder =
                RunMetricSnapshotBatchWriteItemDto.builder()
                        .tsmdId(UUID.randomUUID())
                        .tsmdName(metricName)
                        .metricDeclarationId(UUID.randomUUID())
                        .metricDeclarationVersionId(UUID.randomUUID());
        if (outputSchema != null) {
            itemBuilder.outputSchema(outputSchema);
        }
        RunMetricSnapshotBatchWriteRequestDto snapshotRequest = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .snapshots(List.of(itemBuilder.build()))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(snapshotRequest), BatchWriteResponseDto.class);
    }

    private void insertEvalSummaries(UUID suiteId, UUID runId, UUID computationId, int count) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
            metricValues.putObject("Accuracy").put("score", 0.9 + i * 0.01);
            ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
            testCaseData.put("prompt", "p-" + i);
            items.add(EvalSummaryBatchWriteItemDto.builder()
                    .testCaseRunResultId(UUID.randomUUID())
                    .testCaseId(UUID.randomUUID())
                    .testCaseName("case-" + i)
                    .runIndex(0)
                    .testCaseData(testCaseData)
                    .executionStatus(ExecutionStatus.SUCCESS)
                    .execDurationMs(123L)
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

    private void insertNamedEvalSummaries(UUID suiteId, UUID runId, UUID computationId, List<String> names) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>(names.size());
        for (String name : names) {
            items.add(buildBasicSummaryItem(UUID.randomUUID(), name));
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

    private EvalSummaryBatchWriteItemDto buildBasicSummaryItem(UUID testCaseId, String name) {
        ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
        metricValues.putObject("Accuracy").put("score", 0.95);
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "prompt-" + name);
        return EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(testCaseId)
                .testCaseName(name)
                .runIndex(0)
                .testCaseData(testCaseData)
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(150L)
                .responseStatusCode(200)
                .metricValues(metricValues)
                .build();
    }

    private void insertTwoRunMetricSnapshots(
            UUID runId,
            UUID computationId,
            String firstName,
            JsonNode firstSchema,
            String secondName,
            JsonNode secondSchema) {
        RunMetricSnapshotBatchWriteRequestDto snapshotRequest = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .snapshots(List.of(
                        RunMetricSnapshotBatchWriteItemDto.builder()
                                .tsmdId(UUID.randomUUID())
                                .tsmdName(firstName)
                                .metricDeclarationId(UUID.randomUUID())
                                .metricDeclarationVersionId(UUID.randomUUID())
                                .outputSchema(firstSchema)
                                .build(),
                        RunMetricSnapshotBatchWriteItemDto.builder()
                                .tsmdId(UUID.randomUUID())
                                .tsmdName(secondName)
                                .metricDeclarationId(UUID.randomUUID())
                                .metricDeclarationVersionId(UUID.randomUUID())
                                .outputSchema(secondSchema)
                                .build()))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(snapshotRequest), BatchWriteResponseDto.class);
    }

    private void insertRichSummaries(UUID suiteId, UUID runId, UUID computationId) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>(2);
        for (int runIndex = 0; runIndex < 2; runIndex++) {
            ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
            testCaseData.put("prompt", "Hi there");
            testCaseData.put("attachment", "@ef/files/input.pdf");

            ObjectNode extractedColumns = JsonNodeFactory.instance.objectNode();
            extractedColumns.put("answer", "42");
            extractedColumns.put("audio", runIndex == 0 ? "@ef/files/answer.png" : "@ef/files/audio.mp3");

            ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
            metricValues
                    .putObject("Accuracy")
                    .put("score", 0.9 + runIndex * 0.05)
                    .put("confidence", 0.8);
            // Metric output values must be numeric or null per the eval summary write contract.
            metricValues.putObject("Relevance").put("score", 0.85).put("explanation", 0.7);

            items.add(EvalSummaryBatchWriteItemDto.builder()
                    .testCaseRunResultId(UUID.randomUUID())
                    .testCaseId(UUID.randomUUID())
                    .testCaseName("rich-case-" + runIndex)
                    .runIndex(runIndex)
                    .testCaseData(testCaseData)
                    .extractedColumns(extractedColumns)
                    .executionStatus(ExecutionStatus.SUCCESS)
                    .execDurationMs(200L)
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
