package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;

/**
 * Unit tests for the chain-orchestration logic (Decision 8/9/10/11 of the {@code add-multi-request-suite}
 * change's {@code design.md}): building the ordered {@link RequestExecutionSpec} list from the run
 * snapshot, threading one accumulated frame from each request into the next, concatenating rows in chain
 * order, and stopping at the first {@link RequestExecutionResult#aborted()}. {@link TurnLoopExecutor} is
 * mocked — its own per-request/per-turn behavior is covered by {@link TurnLoopExecutorTest}.
 */
@DisplayName("RequestChainExecutor")
@ExtendWith(MockitoExtension.class)
class RequestChainExecutorTest {

    @Mock
    private TurnLoopExecutor turnLoopExecutor;

    private RequestChainExecutor chainExecutor;

    @BeforeEach
    void setUp() {
        chainExecutor = new RequestChainExecutor(turnLoopExecutor);
    }

    private TestCaseRunInput.TestCaseRunInputBuilder baseInputBuilder() {
        return TestCaseRunInput.builder().runId(UUID.randomUUID()).position(0).testCaseId(UUID.randomUUID());
    }

    private EvaluationContext.EvaluationContextBuilder baseContextBuilder() {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(UUID.randomUUID())
                .numberOfRuns(1)
                .numberOfTestCases(1)
                .cancellationSignal(new AtomicBoolean(false))
                .createdAtMs(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli())
                .snapshotEndpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .snapshotRequestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("messages", "hi"))
                                .build())
                        .build())
                .snapshotInputBindings(List.of())
                .snapshotResponseColumns(List.of())
                .snapshotTestCaseSchema(List.of());
    }

    private TestCaseRunResult successRow() {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .executionStatus(ExecutionStatus.SUCCESS)
                .build();
    }

    @Test
    @DisplayName("Empty additionalRequests executes exactly one request with totalRequests=1")
    void emptyAdditionalRequests_executesOneRequest() {
        TestCaseRunInput input = baseInputBuilder().testCaseName("case-1").build();
        EvaluationContext context =
                baseContextBuilder().snapshotAdditionalRequests(List.of()).build();

        when(turnLoopExecutor.execute(any(), any(), eq(0), any(), any(), anyString(), anyLong()))
                .thenReturn(new RequestExecutionResult(List.of(successRow()), Map.of(), false));

        List<TestCaseRunResult> rows = chainExecutor.execute(input, context, 0, "trace-1", 1000L);

        assertThat(rows).hasSize(1);
        ArgumentCaptor<RequestExecutionSpec> specCaptor = ArgumentCaptor.forClass(RequestExecutionSpec.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> frameCaptor = ArgumentCaptor.forClass(Map.class);
        verify(turnLoopExecutor)
                .execute(
                        eq(input),
                        eq(context),
                        eq(0),
                        specCaptor.capture(),
                        frameCaptor.capture(),
                        eq("trace-1"),
                        eq(1000L));
        assertThat(specCaptor.getValue().requestIndex()).isZero();
        assertThat(specCaptor.getValue().totalRequests()).isEqualTo(1);
        assertThat(frameCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("Two-request chain executes request #0 then the additional request, in order")
    void twoRequestChain_executesInOrder() {
        TestCaseRunInput input = baseInputBuilder().testCaseName("case-2").build();
        RequestDefinitionDto additional = RequestDefinitionDto.builder()
                .name("second")
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/second")
                        .build())
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/second").build())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .inputBindings(List.of())
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestName("configure")
                .snapshotAdditionalRequests(List.of(additional))
                .build();

        Map<String, Object> request0Frame = Map.of("configId", "cfg-1");
        when(turnLoopExecutor.execute(any(), any(), eq(0), any(), any(), anyString(), anyLong()))
                .thenReturn(new RequestExecutionResult(List.of(successRow()), request0Frame, false))
                .thenReturn(new RequestExecutionResult(List.of(successRow(), successRow()), Map.of(), false));

        List<TestCaseRunResult> rows = chainExecutor.execute(input, context, 0, "trace-2", 1000L);

        assertThat(rows).hasSize(3);
        ArgumentCaptor<RequestExecutionSpec> specCaptor = ArgumentCaptor.forClass(RequestExecutionSpec.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> frameCaptor = ArgumentCaptor.forClass(Map.class);
        verify(turnLoopExecutor, times(2))
                .execute(
                        eq(input),
                        eq(context),
                        eq(0),
                        specCaptor.capture(),
                        frameCaptor.capture(),
                        eq("trace-2"),
                        eq(1000L));
        List<RequestExecutionSpec> specs = specCaptor.getAllValues();
        List<Map<String, Object>> frames = frameCaptor.getAllValues();

        RequestExecutionSpec spec0 = specs.get(0);
        assertThat(spec0.requestIndex()).isZero();
        assertThat(spec0.totalRequests()).isEqualTo(2);
        assertThat(spec0.name()).isEqualTo("configure");
        assertThat(frames.get(0)).isEmpty();

        RequestExecutionSpec spec1 = specs.get(1);
        assertThat(spec1.requestIndex()).isEqualTo(1);
        assertThat(spec1.totalRequests()).isEqualTo(2);
        assertThat(spec1.name()).isEqualTo("second");
        assertThat(spec1.endpointRef().getRelativeUrlPattern()).isEqualTo("/v1/second");
        assertThat(spec1.responseColumns())
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("answer");
        // Frame accumulation: request #0's returned frame seeds request #1's initialFrame parameter verbatim.
        assertThat(frames.get(1)).isEqualTo(request0Frame);
    }

    @Test
    @DisplayName("Fail-fast: an aborted request #0 stops the chain, request #1 is never invoked")
    void abortedFirstRequest_stopsChainBeforeSecondRequest() {
        TestCaseRunInput input = baseInputBuilder().testCaseName("case-3").build();
        RequestDefinitionDto additional = RequestDefinitionDto.builder()
                .name("second")
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/second").build())
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotAdditionalRequests(List.of(additional))
                .build();

        TestCaseRunResult errorRow = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .executionStatus(ExecutionStatus.ERROR)
                .build();
        when(turnLoopExecutor.execute(any(), any(), eq(0), any(), any(), anyString(), anyLong()))
                .thenReturn(new RequestExecutionResult(List.of(errorRow), Map.of(), true));

        List<TestCaseRunResult> rows = chainExecutor.execute(input, context, 0, "trace-3", 1000L);

        assertThat(rows).containsExactly(errorRow);
        verify(turnLoopExecutor, times(1)).execute(any(), any(), eq(0), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Null additionalRequests is treated as an empty chain (legacy/defensive)")
    void nullAdditionalRequests_treatedAsEmptyChain() {
        TestCaseRunInput input = baseInputBuilder().testCaseName("case-4").build();
        EvaluationContext context = baseContextBuilder().build();

        when(turnLoopExecutor.execute(any(), any(), eq(0), any(), any(), anyString(), anyLong()))
                .thenReturn(new RequestExecutionResult(List.of(successRow()), Map.of(), false));

        List<TestCaseRunResult> rows = chainExecutor.execute(input, context, 0, "trace-4", 1000L);

        assertThat(rows).hasSize(1);
        verify(turnLoopExecutor, times(1))
                .execute(eq(input), eq(context), eq(0), any(), any(), eq("trace-4"), eq(1000L));
    }

    @Test
    @DisplayName("Bindings from additionalRequests default to an empty binding/column list, not null")
    void additionalRequest_missingBindingsAndColumnsDefaultToEmpty() {
        TestCaseRunInput input = baseInputBuilder().testCaseName("case-5").build();
        RequestDefinitionDto additional = RequestDefinitionDto.builder()
                .name("second")
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/second").build())
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotAdditionalRequests(List.of(additional))
                .build();

        when(turnLoopExecutor.execute(any(), any(), eq(0), any(), any(), anyString(), anyLong()))
                .thenReturn(new RequestExecutionResult(List.of(successRow()), Map.of(), false))
                .thenReturn(new RequestExecutionResult(List.of(successRow()), Map.of(), false));

        chainExecutor.execute(input, context, 0, "trace-5", 1000L);

        ArgumentCaptor<RequestExecutionSpec> specCaptor = ArgumentCaptor.forClass(RequestExecutionSpec.class);
        verify(turnLoopExecutor, times(2))
                .execute(eq(input), eq(context), eq(0), specCaptor.capture(), any(), eq("trace-5"), eq(1000L));
        RequestExecutionSpec spec1 = specCaptor.getAllValues().get(1);
        assertThat(spec1.inputBindings()).isEmpty();
        assertThat(spec1.responseColumns()).isEmpty();
    }
}
