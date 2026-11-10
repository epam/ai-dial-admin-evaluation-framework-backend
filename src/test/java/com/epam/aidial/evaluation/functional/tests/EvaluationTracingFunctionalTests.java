package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.constants.SecurityConstants;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

@DisplayName("Evaluation run tracing")
public abstract class EvaluationTracingFunctionalTests extends AbstractMultiTurnFunctionalTest {

    @Autowired
    private InMemorySpanExporter exporter;

    @Autowired
    private TestSuiteRunRepository runRepository;

    @Autowired
    private TestCaseRunResultRepository resultRepository;

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarations;

    @Autowired
    private MetricProviderClient metricProvider;

    @Test
    @DisplayName("public direct dispatch completes evaluation and parents orchestration to the incoming server span")
    void directDispatchContinuesIncomingTrace() {
        final TestSuiteResponseDto suite = createChatSuite("Tracing");
        createSingleTurnCase(suite.getDatasetId(), "case", Map.of("prompt", "hello"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("answer"));
        final SpanContext incoming = SpanContext.createFromRemoteParent(
                "11111111111111111111111111111111",
                "2222222222222222",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
        headers.set(SecurityConstants.CORRELATION_ID_HEADER_NAME, "incomingCorrelation123");
        final TestSuiteRunRequestDto request = TestSuiteRunRequestDto.builder()
                .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                .build();
        final ResponseEntity<TestSuiteRunResponseDto> response;
        try (Scope ignored = Span.wrap(incoming).makeCurrent()) {
            response = restTemplate.postForEntity(
                    apiUrl("/test-suites/" + suite.getId() + "/runs"),
                    new HttpEntity<>(request, headers),
                    TestSuiteRunResponseDto.class);
        }
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst(SecurityConstants.CORRELATION_ID_HEADER_NAME))
                .isEqualTo("incomingCorrelation123");
        assertThat(response.getBody()).isNotNull();
        final TestSuiteRunResponseDto run = awaitRunTerminal(response.getBody().getId(), 15);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(runRepository.findById(run.getId())).hasValueSatisfying(stored -> {
            assertThat(stored.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
            assertThat(stored.getNumberOfTestCases()).isEqualTo(1);
        });
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(exporter.getFinishedSpanItems()).anySatisfy(span -> {
                    assertThat(span.getName()).isEqualTo(TracingConstants.SPAN_EVAL_RUN);
                    assertThat(span.getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_ID)))
                            .isEqualTo(run.getId().toString());
                }));
        final SpanData orchestration = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(TracingConstants.SPAN_EVAL_RUN))
                .filter(span -> run.getId()
                        .toString()
                        .equals(span.getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_ID))))
                .findFirst()
                .orElseThrow();
        assertThat(orchestration.getTraceId()).isEqualTo(incoming.getTraceId());
        assertThat(orchestration.getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_SUITE_ID)))
                .isEqualTo(suite.getId().toString());
        assertThat(exporter.getFinishedSpanItems()).anySatisfy(span -> {
            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(span.getSpanId()).isEqualTo(orchestration.getParentSpanId());
            assertThat(span.getTraceId()).isEqualTo(incoming.getTraceId());
        });
        verify(deploymentInvoker).invokeWithStreaming(any(), any(), any(), any(), any());
        final SpanData root =
                spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE).getFirst();
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(root.getTraceId()).isNotEqualTo(orchestration.getTraceId());
        assertThat(root.getLinks())
                .singleElement()
                .satisfies(l -> assertThat(l.getSpanContext()).isEqualTo(orchestration.getSpanContext()));
        assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_EXECUTE))
                .singleElement()
                .satisfies(s -> assertThat(s.getParentSpanId()).isEqualTo(root.getSpanId()));
        assertThat(rowsFor(run))
                .singleElement()
                .satisfies(row -> assertThat(row.getTraceId()).isEqualTo(root.getTraceId()));
    }

    protected List<SpanData> spansFor(UUID runId, String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .filter(s -> runId.toString()
                        .equals(s.getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_ID))))
                .toList();
    }

    protected List<TestCaseRunResult> rowsFor(TestSuiteRunResponseDto run) {
        return resultRepository
                .findAll(
                        List.of(FilterCondition.builder()
                                .field("runId")
                                .operator(FilterOperator.EQ)
                                .rawValue(run.getId().toString())
                                .build()),
                        run.getCreatedAt(),
                        null,
                        100)
                .content();
    }

    @Test
    @DisplayName("CSV import preserves historical execution trace IDs while metrics get new linked case roots")
    void importedMetricsUseFreshRoots() throws Exception {
        final var suite = createChatSuite("Imported tracing");
        createSingleTurnCase(suite.getDatasetId(), "imported", Map.of("prompt", "hello"));
        addTracingMetric(suite.getId());
        final String historical = "77777777777777777777777777777777";
        final StringWriter content = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(
                content,
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader(
                                "testCaseName",
                                "runIndex",
                                "executionStatus",
                                "startedAt",
                                "completedAt",
                                "traceId",
                                "testCaseData",
                                "extractedColumns")
                        .get())) {
            csv.printRecord(
                    "imported",
                    0,
                    "SUCCESS",
                    1000,
                    1100,
                    historical,
                    objectMapper.writeValueAsString(Map.of("prompt", "hello")),
                    objectMapper.writeValueAsString(Map.of("answer", "answer")));
        }
        final var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(content.toString().getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "results.csv";
            }
        });
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        final var response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs/import"),
                new HttpEntity<>(body, headers),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final var run = awaitRunTerminal(response.getBody().getId(), 20);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_RUN))
                        .hasSize(1));
        assertThat(rowsFor(run))
                .singleElement()
                .satisfies(row -> assertThat(row.getTraceId()).isEqualTo(historical));
        final var root =
                spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE).getFirst();
        assertThat(root.getTraceId()).isNotEqualTo(historical);
        assertThat(root.getLinks())
                .singleElement()
                .satisfies(link -> assertThat(link.getSpanContext())
                        .isEqualTo(spansFor(run.getId(), TracingConstants.SPAN_EVAL_RUN)
                                .getFirst()
                                .getSpanContext()));
        assertThat(spansFor(run.getId(), TracingConstants.SPAN_METRIC_TSMD_EVALUATE))
                .singleElement()
                .satisfies(metric -> assertThat(metric.getParentSpanId()).isEqualTo(root.getSpanId()));
        assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_EXECUTE))
                .isEmpty();
    }

    protected void addTracingMetric(UUID suiteId) {
        metricDeclarations.insertSeedMetricDeclarations();
        metricDeclarations.insertSeedVersionForAccuracy();
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suiteId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("770e8400-e29b-41d4-a716-446655440001"),
                "Accuracy",
                "[]",
                "[]");
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suiteId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("770e8400-e29b-41d4-a716-446655440001"),
                "NeverRun",
                "[]",
                "[]",
                "false");
        when(metricProvider.evaluate(anyString(), any())).thenAnswer(inv -> {
            final Span provider = Span.current();
            assertThat(provider.getSpanContext().isValid()).isTrue();
            return EvaluationResponseDto.builder()
                    .metricName("Accuracy")
                    .output(Map.of(
                            "score",
                            MetricOutputFieldDto.builder()
                                    .type("value")
                                    .value(BigDecimal.ONE)
                                    .build()))
                    .build();
        });
    }

    @Test
    @DisplayName(
            "multiple requests and turns across repetitions retain independent case traces with Phase 2 metric children")
    void multiRequestMultiTurnMetricHierarchy() {
        final RequestDefinitionDto additional = RequestDefinitionDto.builder()
                .name("second")
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("prompt", "${{prompt}}"))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("secondAnswer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();
        final TestSuiteResponseDto suite = createChatSuite("Traced chain", null, List.of(), List.of(additional));
        createMultiTurnCase(suite.getDatasetId(), "first", List.of(Map.of("prompt", "one"), Map.of("prompt", "two")));
        createMultiTurnCase(
                suite.getDatasetId(), "second", List.of(Map.of("prompt", "three"), Map.of("prompt", "four")));
        addTracingMetric(suite.getId());
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("answer"));
        final var response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(2).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final TestSuiteRunResponseDto run = awaitRunTerminal(response.getBody().getId(), 20);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_RUN))
                        .hasSize(1));
        final List<SpanData> roots = spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE);
        final List<SpanData> executions = spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_EXECUTE);
        final List<SpanData> metrics = spansFor(run.getId(), TracingConstants.SPAN_METRIC_TSMD_EVALUATE);
        assertThat(roots).hasSize(4);
        assertThat(roots.stream().map(SpanData::getTraceId).distinct().toList()).hasSize(4);
        assertThat(executions).hasSize(4);
        assertThat(metrics).hasSize(16);
        assertThat(rowsFor(run)).hasSize(16).allSatisfy(row -> {
            final SpanData root = roots.stream()
                    .filter(s -> row.getTraceId().equals(s.getTraceId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(root.getAttributes().get(AttributeKey.stringKey(TracingConstants.TESTCASE_ID)))
                    .isEqualTo(row.getTestCaseId().toString());
            assertThat(root.getAttributes().get(AttributeKey.stringKey(TracingConstants.RUN_INDEX)))
                    .isEqualTo(Integer.toString(row.getRunIndex()));
            assertThat(metrics).anySatisfy(metric -> {
                assertThat(metric.getAttributes().get(AttributeKey.stringKey(TracingConstants.RESULT_ID)))
                        .isEqualTo(row.getId().toString());
                assertThat(metric.getParentSpanId()).isEqualTo(root.getSpanId());
                assertThat(metric.getTraceId()).isEqualTo(root.getTraceId());
                assertThat(root.getStartEpochNanos()).isLessThanOrEqualTo(metric.getStartEpochNanos());
                assertThat(root.getEndEpochNanos()).isGreaterThanOrEqualTo(metric.getEndEpochNanos());
            });
        });
    }
}
