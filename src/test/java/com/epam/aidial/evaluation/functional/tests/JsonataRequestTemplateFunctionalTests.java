package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Functional tests for the {@code jsonata-request-templates} change's unified {@code TurnLoopExecutor}
 * contract, beyond what {@link MultiTurnRunFunctionalTests} (chat-completions-shaped, non-streaming
 * history accumulation) already covers:
 *
 * <ul>
 *   <li>a non-chat-completions (OpenAI Responses-API-shaped) JSONata request body driving a multi-turn
 *       run, proving the executor has no hardcoded {@code messages}/{@code choices} dependency;
 *   <li>a streaming multi-turn run whose SSE chunks carry DIAL {@code custom_content} (attachments split
 *       across chunks), proving the assembled {@code choices[0].message.custom_content} is what gets
 *       persisted and response-column-extracted from;
 *   <li>a single-turn suite authored with the legacy structural {@code Map} body, proving the echo path
 *       (JSON is a syntactic subset of JSONata) is unchanged inside the unified loop;
 *   <li>a multi-turn case whose bound input fields are not {@code perTurn}, proving the loop collapses to
 *       exactly one request built from the case's shared data ({@code N = 1}), never resending per turn.
 * </ul>
 */
@DisplayName("JSONata Request Template Functional Tests")
public abstract class JsonataRequestTemplateFunctionalTests extends AbstractMultiTurnFunctionalTest {

    @Test
    @DisplayName("Multi-turn run with an OpenAI Responses-API-shaped JSONata body has no messages/choices dependency")
    void respondsApiShapedBody_multiTurn_accumulatesHistoryWithoutMessagesOrChoices() {
        TestSuiteResponseDto suite = createResponsesApiSuite("MT Responses API");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(datasetId, "conv-responses", List.of(Map.of("q", "q0"), Map.of("q", "q1")));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> responsesApiReply("reply-" + call.getAndIncrement()));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results)
                .allSatisfy(r ->
                        assertThat(String.valueOf(r.get("execution_status"))).isEqualTo("SUCCESS"));

        // Turn 1's body carries turn 0's user question, turn 0's assistant reply (fed via the $history
        // frame variable bound from the "history" response column), and turn 1's own question — with no
        // top-level "messages" array or "choices[0].message" reply path ever required.
        Map<String, Object> turn1 = results.stream()
                .filter(r -> ((Number) r.get("turn_index")).intValue() == 1)
                .findFirst()
                .orElseThrow();
        String turn1Request = String.valueOf(turn1.get("request_body"));
        assertThat(turn1Request).contains("q0").contains("reply-0").contains("q1");
        assertThat(turn1Request).doesNotContain("\"messages\"").doesNotContain("\"choices\"");
    }

    @Test
    @DisplayName("Streaming multi-turn run merges DIAL custom_content attachments split across SSE chunks into the "
            + "persisted response")
    void streamingMultiTurn_mergesCustomContentAttachmentsAcrossChunks() {
        TestSuiteResponseDto suite = createChatSuite("MT streaming custom_content");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(datasetId, "conv-streaming", List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> streamingReplyWithAttachment(call.getAndIncrement()));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        for (Map<String, Object> result : results) {
            assertThat(String.valueOf(result.get("execution_status"))).isEqualTo("SUCCESS");
            int turnIndex = ((Number) result.get("turn_index")).intValue();

            JsonNode responseBody = objectMapper.readTree(String.valueOf(result.get("response_body")));
            JsonNode message = responseBody.path("choices").path(0).path("message");
            assertThat(message.path("content").asString()).isEqualTo("reply-" + turnIndex);

            // The attachment's "type" (chunk 1) and "title" (chunk 2) are split across two SSE deltas,
            // keyed by the same "index" — CustomContentAccumulator must merge them into one element of
            // the assembled choices[0].message.custom_content.attachments array.
            JsonNode attachment =
                    message.path("custom_content").path("attachments").path(0);
            assertThat(attachment.path("type").asString()).isEqualTo("image/png");
            assertThat(attachment.path("title").asString()).isEqualTo("doc-" + turnIndex + ".png");
        }
    }

    @Test
    @DisplayName("Single-turn suite with a legacy Map request body runs unchanged through the unified turn loop")
    void legacyMapBody_singleTurn_runsUnchanged() {
        TestSuiteResponseDto suite = createChatSuite("MT legacy map body");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createSingleTurnCase(datasetId, "single-legacy", Map.of("prompt", "hello"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("legacy-answer"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> result = results.get(0);
        assertThat(String.valueOf(result.get("execution_status"))).isEqualTo("SUCCESS");
        // Single-turn rows keep the builder defaults (0/1), byte-identical to the pre-turn-loop shape.
        assertThat(((Number) result.get("turn_index")).intValue()).isZero();
        assertThat(((Number) result.get("total_turns")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(result.get("request_body"))).contains("hello");
        assertThat(String.valueOf(result.get("extracted_columns"))).contains("legacy-answer");

        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Suite with a bare-placeholder JSONata body (no surrounding quotes) is created and runs end-to-end")
    void barePlaceholderBody_singleTurn_createsAndRunsEndToEnd() {
        TestSuiteResponseDto suite = createBarePlaceholderSuite("MT bare placeholder body");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createSingleTurnCase(datasetId, "single-bare", Map.of("prompt", "hello-bare"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("bare-answer"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> result = results.get(0);
        assertThat(String.valueOf(result.get("execution_status"))).isEqualTo("SUCCESS");
        // The bare placeholder (no surrounding quotes: "content": ${{prompt}}) resolved to the JSON
        // serialization of the bound String value ("hello-bare"), proving the write-time-accepted bare
        // mode also evaluates correctly at run time.
        assertThat(String.valueOf(result.get("request_body"))).contains("hello-bare");
        assertThat(String.valueOf(result.get("extracted_columns"))).contains("bare-answer");
    }

    @Test
    @DisplayName("Multi-turn case with no perTurn binding collapses to a single request built from shared data")
    void noPerTurnBinding_multiTurnCase_collapsesToOneRequestFromSharedData() {
        TestSuiteResponseDto suite = createNoPerTurnBindingSuite("MT no-perturn-binding");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(
                datasetId,
                "conv-no-perturn",
                Map.of("topic", "static-topic"),
                List.of(Map.of("prompt", "a"), Map.of("prompt", "b")));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("static-reply"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> result = results.get(0);
        assertThat(String.valueOf(result.get("execution_status"))).isEqualTo("SUCCESS");
        assertThat(((Number) result.get("turn_index")).intValue()).isZero();
        assertThat(((Number) result.get("total_turns")).intValue()).isEqualTo(1);
        // The request is built from shared `data` only ("static-topic") — the two multiTurnData entries
        // ("a"/"b") are never referenced because no bound input field is perTurn, so the loop never
        // resends the request per turn.
        assertThat(String.valueOf(result.get("request_body"))).contains("static-topic");

        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    // -------------------- fixtures --------------------

    /**
     * DEPLOYMENT suite shaped like the OpenAI Responses API ({@code input}/{@code output}, not
     * {@code messages}/{@code choices}), authored as a JSONata source-string body. History is
     * accumulated purely via the {@code history} response column and the {@code $history} frame
     * variable — there is nothing chat-completions-specific about it.
     */
    private TestSuiteResponseDto createResponsesApiSuite(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name + " " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/responses")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("q")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .perTurn(true)
                        .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/responses")
                        .body(JsonRequestBodyDto.builder()
                                .content("{\"input\": $append($history, "
                                        + "[{\"role\": \"user\", \"content\": \"${{q}}\"}]), \"model\": \"gpt-x\"}")
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("q")
                        .dataField("q")
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("history")
                        .expression("$append($request.input, [{\"role\": \"assistant\", "
                                + "\"content\": $response.output[0].content[0].text}])")
                        .type(SchemaFieldType.ARRAY)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /** A non-streaming Responses-API-shaped reply: {@code {"output":[{"content":[{"text": ...}]}]}}. */
    private DeploymentInvocationResult responsesApiReply(String text) {
        return new DeploymentInvocationResult(
                200,
                false,
                Map.of("output", List.of(Map.of("content", List.of(Map.of("text", text))))),
                null,
                new HttpHeaders());
    }

    /**
     * DEPLOYMENT suite authored as a JSONata source-string body with a bare placeholder — i.e. the
     * placeholder appears outside any string literal, as the entire value of the {@code content} field
     * ({@code "content": ${{prompt}}}, no surrounding quotes) rather than quoted or embedded. Proves the
     * write-time-accepted bare mode (see {@code TestSuiteRequestValidator}'s placeholder-neutralized
     * validation) round-trips correctly at run time too.
     */
    private TestSuiteResponseDto createBarePlaceholderSuite(String name) {
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
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content("{\"messages\": [{\"role\": \"user\", \"content\": ${{prompt}}}]}")
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
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /**
     * A streaming reply whose DIAL {@code custom_content.attachments[0]} is split across two SSE deltas
     * (index 0's {@code type} in the first chunk, {@code title} in the second) — {@link
     * com.epam.aidial.evaluation.runner.job.CustomContentAccumulator} must merge them by index.
     */
    private DeploymentInvocationResult streamingReplyWithAttachment(int turnIndex) {
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"reply-" + turnIndex
                + "\",\"custom_content\":{\"attachments\":[{\"index\":0,\"type\":\"image/png\"}]}}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"custom_content\":"
                + "{\"attachments\":[{\"index\":0,\"title\":\"doc-" + turnIndex + ".png\"}]}}}]}\n\n"
                + "data: [DONE]\n\n";
        InputStream eventStream = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
        return new DeploymentInvocationResult(200, true, null, eventStream, new HttpHeaders());
    }

    /**
     * DEPLOYMENT suite bound to a dataset with one shared field ({@code topic}, not perTurn) and one
     * per-turn field ({@code prompt}) that the suite's single input binding never references. Per {@link
     * com.epam.aidial.evaluation.runner.job.PerTurnBindingDetector}, a multi-turn case built
     * against this suite must collapse to {@code N = 1} regardless of how many {@code multiTurnData}
     * entries it carries.
     */
    private TestSuiteResponseDto createNoPerTurnBindingSuite(String name) {
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
                                .name("topic")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .perTurn(true)
                                .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("messages", List.of(Map.of("role", "user", "content", "${{topic}}"))))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("topic")
                        .dataField("topic")
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
