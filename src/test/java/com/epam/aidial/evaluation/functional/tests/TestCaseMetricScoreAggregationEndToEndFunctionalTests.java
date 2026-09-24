package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.dto.overallscore.Mean;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end repro of the reported bug, driven through a real suite run (not synthetically seeded
 * fixtures): one test case with 2 turns (uneven row count vs. a second, single-turn test case) where a
 * metric's {@code condition} fires on only one of its rows, combined with {@code overallScore: mean}.
 * Exercises the full pipeline — Phase 1 execution, Phase 2's aggregation
 * ({@code test_case_metric_scores_aggregated}) and per-test-case scoring
 * ({@code eval_summaries.score}/{@code .passed}), and Phase 3's run-level {@code overall} — rather than
 * any single layer in isolation (see {@code TestCaseMetricScoreAggregatorFunctionalTests} and
 * {@code MetricScoreComputationFunctionalTests} for the layer-level coverage this complements).
 */
@DisplayName("Test Case Metric Score Aggregation — End-to-End Functional Tests")
public abstract class TestCaseMetricScoreAggregationEndToEndFunctionalTests extends AbstractMultiTurnFunctionalTest {

    @Autowired
    private MetricProviderClient metricProviderClient;

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarationTestDataProvider;

    @Autowired
    private MetricScoreResultRepository metricScoreResultRepository;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    @Test
    @DisplayName("A conditionally-skipped metric no longer poisons the row it's absent from, and a "
            + "multi-turn test case no longer outweighs a single-turn one in the run-level mean")
    void endToEndUnevenRowCountsWithConditionallySkippedMetric() {
        // MetricMain always fires; its value is the turn's own "category" field, read back verbatim by the
        // mocked metric provider. MetricBonus only fires on turn 0 (condition "turn.index = 0") and always
        // reports a constant 0.6, so a test case's aggregated MetricBonus average is 0.6 regardless of its
        // turn count — it exists purely to exercise "absent on some rows" for a test case with >1 row.
        TestSuiteResponseDto suite = createChatSuiteWithMeanOverallScore("Suite For End-To-End Aggregation Repro");

        // caseA: 2 turns, MetricMain fires 0.8 on both -> aggregated avg = 0.8, count = 2.
        //        MetricBonus fires only on turn 0 (0.6) -> aggregated avg = 0.6, count = 1 (turn 1 absent,
        //        excluded rather than coalesced to 0 — this is the per-row-score bug fix under test).
        //        Per-row score (mean of the two metrics' per-test-case averages) = (0.8 + 0.6) / 2 = 0.7,
        //        identical for BOTH of caseA's rows (turn 0 and turn 1).
        UUID datasetId = suite.getDatasetId();
        createMultiTurnCase(
                datasetId,
                "case-a-multi-turn",
                List.of(
                        Map.of("prompt", "turn zero", "category", "0.8"),
                        Map.of("prompt", "turn one", "category", "0.8")));

        // caseB: 1 turn, MetricMain fires 0.2 -> aggregated avg = 0.2, count = 1.
        //        MetricBonus fires on turn 0 (its only turn) -> aggregated avg = 0.6, count = 1.
        //        Per-row score = (0.2 + 0.6) / 2 = 0.4.
        createSingleTurnCase(datasetId, "case-b-single-turn", Map.of("prompt", "only turn", "category", "0.2"));

        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        String mainVersionId = UUID.randomUUID().toString();
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                mainVersionId,
                "00000000-0000-0000-0000-000000000001",
                1,
                "{}",
                "{}",
                "{\"properties\":{\"score\":{\"type\":\"number\"}}}");
        String bonusVersionId = UUID.randomUUID().toString();
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                bonusVersionId,
                "00000000-0000-0000-0000-000000000002",
                1,
                "{}",
                "{}",
                "{\"properties\":{\"score\":{\"type\":\"number\"}}}");

        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString(mainVersionId),
                "MetricMain",
                "[]",
                "[{\"property\": \"value\", \"source\": {\"$type\": \"TestCase\", \"columnName\": \"category\"}}]",
                null);
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString(bonusVersionId),
                "MetricBonus",
                "[]",
                "[{\"property\": \"value\", \"source\": {\"$type\": \"TestCase\", \"columnName\": \"category\"}}]",
                "turn.index = 0");

        // EvaluationRequestDto.getMetricName() is the metric DECLARATION's name (Accuracy/Latency, from the
        // seed catalog — see MetricEvaluationWorker.buildRequest), not the TSMD's own configured name
        // (MetricMain/MetricBonus) — MetricBonus is attached to the "Latency" declaration (id ...0002).
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("ok"));
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenAnswer(invocation -> {
                    EvaluationRequestDto request = invocation.getArgument(1);
                    BigDecimal value = "Latency".equals(request.getMetricName())
                            ? new BigDecimal("0.6")
                            : new BigDecimal(request.getInput().get("value").toString());
                    return EvaluationResponseDto.builder()
                            .metricName(request.getMetricName())
                            .output(Map.of(
                                    "score",
                                    MetricOutputFieldDto.builder()
                                            .type("value")
                                            .value(value)
                                            .build()))
                            .build();
                });

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 15);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        Map<String, EvalSummary> summariesByTestCaseName = fetchEvalSummariesByTestCaseName(run.getId());
        assertThat(summariesByTestCaseName).hasSize(2);

        // caseA has 2 rows (one per turn); both must share the identical, correctly-computed score — the
        // fix under test. Before this change, turn 1's row (where MetricBonus never fired) would have had
        // its own independently-computed score with MetricBonus coalesced to 0: (0.8 + 0) / 2 = 0.4, wrong.
        List<Map<String, Object>> multiTurnCaseRows = fetchEvalSummariesForTestCase(run.getId(), "case-a-multi-turn");
        assertThat(multiTurnCaseRows).hasSize(2);
        for (Map<String, Object> row : multiTurnCaseRows) {
            EvalSummary summary = evalSummaryRepository
                    .findById(UUID.fromString((String) row.get("id")))
                    .orElseThrow();
            assertThat(summary.getScore()).isCloseTo(0.7, within(1e-9));
        }

        EvalSummary caseB = summariesByTestCaseName.get("case-b-single-turn");
        assertThat(caseB.getScore()).isCloseTo(0.4, within(1e-9));

        // Run-level overall (mean): averaged per metric across test cases (each test case contributes
        // exactly one sample per metric, regardless of row count), then averaged across metrics.
        // MetricMain's run-level average is (0.8 + 0.2) / 2 = 0.5 — NOT the row-weighted
        // (0.8 + 0.8 + 0.2) / 3 = 0.6 that a raw-row aggregate would have produced, since caseA's 2 rows
        // would otherwise outweigh caseB's 1. MetricBonus's run-level average is (0.6 + 0.6) / 2 = 0.6.
        // overall = (0.5 + 0.6) / 2 = 0.55.
        List<Map<String, Object>> snapshots = metaTestDataHelper.findRunMetricSnapshotsByRunId(run.getId());
        assertThat(snapshots).hasSize(2);
        UUID computationId = UUID.fromString((String) snapshots.get(0).get("computation_id"));
        List<MetricScoreResult> results =
                metricScoreResultRepository.findByRunAndComputation(run.getId(), computationId);
        MetricScoreResult overall = results.stream()
                .filter(r -> "overall".equals(r.getMetricScoreName()) && "overall".equals(r.getMetricName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing overall metric score result"));
        assertThat(overall.getValue()).isCloseTo(0.55, within(1e-9));
    }

    /**
     * Same shape as {@link AbstractMultiTurnFunctionalTest#createChatSuite(String)}, plus
     * {@code overallScore: mean} — the base helper leaves {@code overallScore} unset (system default),
     * which this test needs configured so both Phase 2's per-row score and Phase 3's run-level overall are
     * actually computed.
     */
    private TestSuiteResponseDto createChatSuiteWithMeanOverallScore(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name + " " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .perTurn(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("category")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .perTurn(true)
                                .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("messages", List.of(Map.of("role", "user", "content", "${{prompt}}"))))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .overallScore(new Mean())
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map<String, EvalSummary> fetchEvalSummariesByTestCaseName(UUID runId) {
        List<Map<String, Object>> rows = analyticsTestDataHelper.findEvalSummariesByRunId(runId);
        Map<String, EvalSummary> byName = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = UUID.fromString((String) row.get("id"));
            EvalSummary summary = evalSummaryRepository.findById(id).orElseThrow();
            byName.put((String) row.get("test_case_name"), summary);
        }
        return byName;
    }

    private List<Map<String, Object>> fetchEvalSummariesForTestCase(UUID runId, String testCaseName) {
        return analyticsTestDataHelper.findEvalSummariesByRunId(runId).stream()
                .filter(row -> testCaseName.equals(row.get("test_case_name")))
                .toList();
    }
}
