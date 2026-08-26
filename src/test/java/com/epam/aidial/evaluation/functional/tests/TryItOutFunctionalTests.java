package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutWithVariablesRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Try It Out Functional Tests")
public abstract class TryItOutFunctionalTests extends AbstractMultiTurnFunctionalTest {

    // --- 6.4 Test-case try-it-out ---

    @Test
    @DisplayName("Should try-it-out with test case and get resolved request + proxied response")
    void shouldTryItOutWithTestCase() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("promptField", "Tell me about AI"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200, false, Map.of("id", "chatcmpl-1", "choices", List.of()), null, new HttpHeaders()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResolvedRequest()).isNotNull();
        assertThat(response.getBody().getResolvedRequest().getUrl()).isEqualTo("/chat/completions");
        assertThat(((ResolvedJsonBodyDto)
                                response.getBody().getResolvedRequest().getBody())
                        .getContent()
                        .get("prompt"))
                .isEqualTo("Tell me about AI");
        assertThat(response.getBody().getResponse()).isNotNull();
        assertThat(response.getBody().getResponse().getStatusCode()).isEqualTo(200);
        assertThat(response.getBody().getDurationMs()).isNotNull();
    }

    // --- try-it-out with a multi-turn test case: executes every turn, threading $history across turns ---

    @Test
    @DisplayName("Should execute every turn of a multi-turn test case and return the final turn's accumulated request")
    void shouldTryItOutWithMultiTurnTestCase() {
        TestSuiteResponseDto suite = createHistoryAccumulatingChatSuite("TryOut MT");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        TestCaseResponseDto tc = createMultiTurnCase(
                datasetId, "conv-1", List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1"), Map.of("prompt", "q2")));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> chatReply("reply-" + call.getAndIncrement()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TryItOutResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getResponse()).isNotNull();
        assertThat(body.getResponse().getStatusCode()).isEqualTo(200);

        // The final turn's request already carries the accumulated history: turns 0 and 1's user
        // messages + assistant replies, since $append($history, [...]) folds every prior turn in.
        String turn2RequestBody =
                objectMapper.writeValueAsString(body.getResolvedRequest().getBody());
        assertThat(turn2RequestBody)
                .contains("q0")
                .contains("reply-0")
                .contains("q1")
                .contains("reply-1")
                .contains("q2");

        // history carries every turn, in order, including the last (which duplicates the top-level fields).
        assertThat(body.getHistory()).hasSize(3);
        String turn0RequestBody = objectMapper.writeValueAsString(
                body.getHistory().get(0).getResolvedRequest().getBody());
        assertThat(turn0RequestBody).contains("q0").doesNotContain("q1");
        assertThat(body.getHistory().get(2).getResponse()).isEqualTo(body.getResponse());
        assertThat(body.getHistory().get(2).getResolvedRequest()).isEqualTo(body.getResolvedRequest());
    }

    @Test
    @DisplayName("Should stop at the first failed turn and return that turn's error response")
    void shouldStopAtFirstFailedTurnForMultiTurnTestCase() {
        TestSuiteResponseDto suite = createHistoryAccumulatingChatSuite("TryOut MT fail-fast");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        TestCaseResponseDto tc =
                createMultiTurnCase(datasetId, "conv-fail", List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("reply-0"))
                .thenReturn(
                        new DeploymentInvocationResult(500, false, Map.of("error", "boom"), null, new HttpHeaders()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TryItOutResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getResponse().getStatusCode()).isEqualTo(500);

        assertThat(body.getHistory()).hasSize(2);
        assertThat(body.getHistory().get(0).getResponse().getStatusCode()).isEqualTo(200);
        assertThat(body.getHistory().get(1).getResponse()).isEqualTo(body.getResponse());
    }

    // --- Fix: a JSON body whose JSONata evaluation fails must abort try-it-out, never invoke the
    // deployment with a silently-dropped body ---

    @Test
    @DisplayName("Should return 400 and never invoke the deployment when the request body's JSONata "
            + "evaluation fails at run time")
    void shouldReturn400AndNeverInvokeDeploymentWhenBodyEvaluationFails() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/chat/completions")
                // Valid JSONata syntax (parses fine at write time) that fails only when evaluated,
                // since the referenced function does not exist.
                .body(JsonRequestBodyDto.builder()
                        .jsonataContent("{\"prompt\": $doesNotExistFunction()}")
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Body Evaluation Failure Suite " + UUID.randomUUID())
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(template)
                .build();
        ResponseEntity<TestSuiteResponseDto> suiteResponse =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(suiteResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = suiteResponse.getBody();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("promptField", "irrelevant"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The evaluation failure was downgraded to a REQUEST_BODY_EVALUATION_ERROR validation warning by
        // RequestResolver's preview path (never a silently-dropped body); TryItOutService then turns that
        // warning into this 400.
        assertThat(response.getBody()).contains("REQUEST_BODY_EVALUATION_ERROR");
        verifyNoInteractions(deploymentInvoker);
    }

    // --- 6.5 Suite-level try-it-out with variables ---

    @Test
    @DisplayName("Should try-it-out with variables and get resolved request + proxied response")
    void shouldTryItOutWithVariables() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200, false, Map.of("id", "chatcmpl-2"), null, new HttpHeaders()));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "Hello from variables"))
                .build();

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"),
                jsonEntity(request),
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResolvedRequest()).isNotNull();
        assertThat(response.getBody().getResolvedRequest().getUrl()).isEqualTo("/chat/completions");
        assertThat(((ResolvedJsonBodyDto)
                                response.getBody().getResolvedRequest().getBody())
                        .getContent()
                        .get("prompt"))
                .isEqualTo("Hello from variables");
        assertThat(response.getBody().getResponse().getStatusCode()).isEqualTo(200);
    }

    // --- 6.6 Error scenarios ---

    @Test
    @DisplayName("Should return 404 for non-existent suite (test-case path)")
    void shouldReturn404ForNonExistentSuiteTestCase() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/test-cases/" + UUID.randomUUID() + "/try-it-out"),
                null,
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 for non-existent test case")
    void shouldReturn404ForNonExistentTestCase() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + UUID.randomUUID() + "/try-it-out"),
                null,
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 for non-existent suite (variables path)")
    void shouldReturn404ForNonExistentSuiteVariables() {
        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "Hello"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/try-it-out"), jsonEntity(request), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 for missing deployment reference")
    void shouldReturn400ForMissingDeploymentRef() {
        TestSuite suite = createSuiteWithoutDeploymentRef();

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "Hello"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"), jsonEntity(request), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for missing request template")
    void shouldReturn400ForMissingTemplate() {
        TestSuiteResponseDto suite = createSuiteWithoutTemplate();

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "Hello"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"), jsonEntity(request), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 502 for DIAL Core connection failure")
    void shouldReturn502ForConnectionFailure() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenThrow(new DialCoreClientException(
                        HttpStatus.BAD_GATEWAY, "Failed to connect to DIAL Core deployment"));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "Hello"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"), jsonEntity(request), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("Should return 504 for DIAL Core timeout")
    void shouldReturn504ForTimeout() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenThrow(new DialCoreClientException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "DIAL Core deployment did not respond within the configured timeout"));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "Hello"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"), jsonEntity(request), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    // --- Encoding regression guard for issue #959 ---

    @Test
    @DisplayName("Should pass pre-encoded deployment id through to invoker without re-encoding")
    void shouldPassPreEncodedDeploymentIdThroughToInvoker() {
        // Deployment id with %20 (the shape DIAL Core's GET /v1/deployments returns for public
        // applications whose name contains spaces, e.g. "Quick App with RAG").
        String preEncodedId = "applications/public/Quick%20App%20with%20RAG__0.0.1";
        // Use a non-OPENAI_STANDARD_PATHS url to route through /v1/deployments/{id}/route/...
        TestSuiteResponseDto suite = createSuiteWithDeploymentIdAndUrl(preEncodedId, "/custom/route");

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200, false, Map.of("id", "chatcmpl-1"), null, new HttpHeaders()));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "Hello"))
                .build();

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"),
                jsonEntity(request),
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(deploymentInvoker).invokeWithStreaming(any(), pathCaptor.capture(), any(), any(), any());

        // The controller/service must pass the pre-encoded id through verbatim (no re-encoding,
        // no double-encoding). Wire-level single-encoding is exercised by
        // DialCoreDeploymentInvokerEncodingTest.
        assertThat(pathCaptor.getValue())
                .isEqualTo("/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/custom/route");
        assertThat(pathCaptor.getValue()).doesNotContain("%2520");
    }

    // --- Multi-request chain try-out (add-multi-request-try-out) ---

    @Test
    @DisplayName("Should thread request #0's real extracted column into request #1's resolved body "
            + "and stamp chain identity on every history entry")
    void shouldThreadExtractedColumnAndStampChainIdentity() {
        TestSuiteResponseDto suite = createTwoRequestChainSuite("Chain Thread", "followup", HttpMethod.POST, true);
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("prompt", "hi"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(configureReply(7))
                .thenReturn(chatReply("final answer"));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TryItOutResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getHistory()).hasSize(2);

        TryItOutResponseDto first = body.getHistory().get(0);
        assertThat(first.getRequestIndex()).isZero();
        assertThat(first.getTotalRequests()).isEqualTo(2);
        assertThat(first.getRequestName()).isNull();
        assertThat(first.getTurnIndex()).isNull();
        assertThat(first.getTotalTurns()).isNull();
        assertThat(first.getExtractedColumns().get("configId").asInt()).isEqualTo(7);

        TryItOutResponseDto second = body.getHistory().get(1);
        assertThat(second.getRequestIndex()).isEqualTo(1);
        assertThat(second.getTotalRequests()).isEqualTo(2);
        assertThat(second.getRequestName()).isEqualTo("followup");
        assertThat(second.getTurnIndex()).isNull();
        assertThat(second.getTotalTurns()).isNull();
        assertThat(second.getExtractedColumns().get("answer").asString()).isEqualTo("final answer");

        // Request #1's outgoing body was resolved with $configId bound to request #0's REAL extracted value.
        String secondRequestBody =
                objectMapper.writeValueAsString(second.getResolvedRequest().getBody());
        assertThat(secondRequestBody).contains("7").contains("hi");

        // Top level mirrors the last executed (second) history entry.
        assertThat(body.getRequestIndex()).isEqualTo(second.getRequestIndex());
        assertThat(body.getTotalRequests()).isEqualTo(second.getTotalRequests());
        assertThat(body.getRequestName()).isEqualTo(second.getRequestName());
        assertThat(body.getResponse()).isEqualTo(second.getResponse());
        assertThat(body.getResolvedRequest()).isEqualTo(second.getResolvedRequest());
    }

    @Test
    @DisplayName("Should stop the chain at the first failed request and never invoke request #1")
    void shouldStopChainAtFirstFailedRequest() {
        TestSuiteResponseDto suite = createTwoRequestChainSuite("Chain Fail", "followup", HttpMethod.POST, true);
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("prompt", "hi"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(
                        new DeploymentInvocationResult(500, false, Map.of("error", "boom"), null, new HttpHeaders()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TryItOutResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getHistory()).hasSize(1);
        assertThat(body.getResponse().getStatusCode()).isEqualTo(500);
        assertThat(body.getHistory().getFirst().getResponse()).isEqualTo(body.getResponse());
        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should invoke each chain request with its own HTTP method")
    void shouldInvokeEachChainRequestWithItsOwnHttpMethod() {
        TestSuiteResponseDto suite = createTwoRequestChainSuite("Chain Method", "followup", HttpMethod.GET, false);
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("prompt", "hi"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(configureReply(7))
                .thenReturn(chatReply("final answer"));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<HttpMethod> methodCaptor = ArgumentCaptor.forClass(HttpMethod.class);
        verify(deploymentInvoker, times(2)).invokeWithStreaming(methodCaptor.capture(), any(), any(), any(), any());
        assertThat(methodCaptor.getAllValues()).containsExactly(HttpMethod.POST, HttpMethod.GET);
    }

    @Test
    @DisplayName("Should execute the chain for variables-mode try-out, threading extracted columns "
            + "and stamping identity")
    void shouldExecuteChainForVariablesMode() {
        TestSuiteResponseDto suite = createTwoRequestChainSuite("Chain Vars", "followup", HttpMethod.POST, true);

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(configureReply(9))
                .thenReturn(chatReply("var answer"));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "HelloVars"))
                .build();

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"),
                jsonEntity(request),
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TryItOutResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getHistory()).hasSize(2);
        assertThat(body.getHistory().get(0).getTotalRequests()).isEqualTo(2);
        assertThat(body.getHistory().get(1).getRequestName()).isEqualTo("followup");

        // Variables mode wholesale-replaces every chain element's own inputBindings: the additional
        // request's body still sees $configId (the real prior response's extracted column) plus the
        // user-supplied "prompt" variable.
        String secondRequestBody = objectMapper.writeValueAsString(
                body.getHistory().get(1).getResolvedRequest().getBody());
        assertThat(secondRequestBody).contains("9").contains("HelloVars");
    }

    @Test
    @DisplayName("Should stop a variables-mode chain at the first failed request")
    void shouldStopVariablesChainAtFirstFailedRequest() {
        TestSuiteResponseDto suite = createTwoRequestChainSuite("Chain Vars Fail", "followup", HttpMethod.POST, true);

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(
                        new DeploymentInvocationResult(500, false, Map.of("error", "boom"), null, new HttpHeaders()));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "x"))
                .build();

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"),
                jsonEntity(request),
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TryItOutResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getHistory()).hasSize(1);
        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 identifying the offending chain element when it lacks an endpoint "
            + "reference, without invoking anything")
    void shouldReturn400ForChainElementMissingEndpointRef() {
        TestSuiteResponseDto suite = createSuiteWithChainElementMissingEndpointRef();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("prompt", "hi"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("additionalRequests[0]");
        verifyNoInteractions(deploymentInvoker);
    }

    @Test
    @DisplayName("Should return 400 naming the broken chain element (not 404) when the testCaseId does not exist")
    void shouldReturn400BeforeNotFoundForBrokenChainElementAndMissingTestCase() {
        TestSuiteResponseDto suite = createSuiteWithChainElementMissingEndpointRef();

        // Chain-element preconditions run on the already-loaded suite BEFORE the test case is looked up,
        // so a misconfigured chain wins over a nonexistent testCaseId (design D7).
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + UUID.randomUUID() + "/try-it-out"),
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("additionalRequests[0]");
        verifyNoInteractions(deploymentInvoker);
    }

    @Test
    @DisplayName("Should keep single-request response unchanged apart from the additive "
            + "extractedColumns/extractionWarnings fields")
    void shouldKeepSingleRequestResponseUnchangedExceptExtractionFields() {
        TestSuiteResponseDto suite = createSuiteWithTemplateAndResponseColumn();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("promptField", "hi"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200, false, Map.of("id", "chatcmpl-99", "choices", List.of()), null, new HttpHeaders()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TryItOutResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getHistory()).isNull();
        assertThat(body.getRequestIndex()).isNull();
        assertThat(body.getTotalRequests()).isNull();
        assertThat(body.getRequestName()).isNull();
        assertThat(body.getTurnIndex()).isNull();
        assertThat(body.getTotalTurns()).isNull();
        assertThat(body.getExtractedColumns()).isNotNull();
        assertThat(body.getExtractedColumns().get("responseId").asString()).isEqualTo("chatcmpl-99");
        assertThat(body.getExtractionWarnings()).isNotNull();
    }

    @Test
    @DisplayName("Should omit extractedColumns/extractionWarnings entirely when the suite defines no "
            + "response columns (byte-identical to the pre-existing response)")
    void shouldOmitExtractionFieldsWhenSuiteHasNoResponseColumns() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("promptField", "hi"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200, false, Map.of("id", "chatcmpl-1", "choices", List.of()), null, new HttpHeaders()));

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .doesNotContain("extractedColumns")
                .doesNotContain("extractionWarnings")
                .doesNotContain("history")
                .doesNotContain("requestIndex")
                .doesNotContain("requestName")
                .doesNotContain("turnIndex")
                .doesNotContain("totalTurns")
                .doesNotContain("totalRequests");
    }

    // --- Helpers ---

    private TestSuiteResponseDto createSuiteWithChainElementMissingEndpointRef() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Chain Missing Endpoint " + UUID.randomUUID())
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/chat/completions")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("op", "configure"))
                                .build())
                        .build())
                .inputBindings(List.of())
                .additionalRequests(List.of(RequestDefinitionDto.builder()
                        .name("broken")
                        .requestTemplate(RequestTemplateDto.builder()
                                .urlTemplate("/v1/followup")
                                .body(JsonRequestBodyDto.builder()
                                        .content(Map.of("op", "ask"))
                                        .build())
                                .build())
                        .inputBindings(List.of(InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("prompt")
                                .build()))
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> suiteResponse =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(suiteResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return suiteResponse.getBody();
    }

    private TestSuiteResponseDto createSuiteWithTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/chat/completions")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}", "temperature", "${{temperature:0.7}}"))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Try It Out Suite " + UUID.randomUUID())
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("promptField")
                        .build()))
                .build();
        ResponseEntity<TestSuiteResponseDto> res =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private TestSuiteResponseDto createSuiteWithTemplateAndResponseColumn() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/chat/completions")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}", "temperature", "${{temperature:0.7}}"))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Try It Out Extraction Suite " + UUID.randomUUID())
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("promptField")
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("responseId")
                        .expression("id")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();
        ResponseEntity<TestSuiteResponseDto> res =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    /**
     * Two-request chain suite for try-out coverage: request #0 ("configure") is unlabelled, produces the
     * {@code configId} response column; the additional request is named {@code secondRequestName} and,
     * when {@code secondHasBody} is {@code true}, consumes {@code configId} from the accumulated frame via
     * raw JSONata ({@code $configId} — the frame is exposed to JSONata expressions, never matched against
     * {@code ${{...}}} placeholders) alongside a {@code ${{prompt}}} data-field binding, producing its own
     * {@code answer} response column. When {@code secondHasBody} is {@code false} the second request has no
     * body at all (used for the per-request-HTTP-method scenario, e.g. a bodyless GET).
     */
    private TestSuiteResponseDto createTwoRequestChainSuite(
            String name, String secondRequestName, HttpMethod secondMethod, boolean secondHasBody) {
        RequestTemplateDto secondTemplate = secondHasBody
                ? RequestTemplateDto.builder()
                        .urlTemplate("/v1/followup")
                        .body(JsonRequestBodyDto.builder()
                                .jsonataContent("{\"cfg\": $configId, \"prompt\": \"${{prompt}}\"}")
                                .build())
                        .build()
                : RequestTemplateDto.builder().urlTemplate("/v1/followup").build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name(name + " " + UUID.randomUUID())
                .deploymentRef(buildDeploymentRef())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/configure")
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/configure")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("op", "configure"))
                                .build())
                        .build())
                .inputBindings(List.of())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("configId")
                        .expression("usage.total_tokens")
                        .type(SchemaFieldType.INTEGER)
                        .build()))
                .additionalRequests(List.of(RequestDefinitionDto.builder()
                        .name(secondRequestName)
                        .endpointRef(EndpointContractDto.builder()
                                .method(secondMethod)
                                .relativeUrlPattern("/v1/followup")
                                .build())
                        .requestTemplate(secondTemplate)
                        .inputBindings(
                                secondHasBody
                                        ? List.of(InputBindingDto.builder()
                                                .templateVariable("prompt")
                                                .dataField("prompt")
                                                .build())
                                        : List.of())
                        .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                                .name("answer")
                                .expression("choices[0].message.content")
                                .type(SchemaFieldType.STRING)
                                .build()))
                        .build()))
                .build();
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private DeploymentInvocationResult configureReply(int totalTokens) {
        return new DeploymentInvocationResult(
                200, false, Map.of("usage", Map.of("total_tokens", totalTokens)), null, new HttpHeaders());
    }

    private TestSuite createSuiteWithoutDeploymentRef() {
        return metaTestDataHelper.createTestSuite("No Deployment Suite " + UUID.randomUUID());
    }

    private TestSuiteResponseDto createSuiteWithoutTemplate() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("No Template Suite " + UUID.randomUUID())
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("col1")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .build();
        ResponseEntity<TestSuiteResponseDto> res =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private TestCaseResponseDto createTestCase(UUID testSuiteId, String name, Map<String, Object> data) {
        UUID datasetId = metaTestDataHelper.getDatasetId(testSuiteId);
        TestCaseRequestDto req =
                TestCaseRequestDto.builder().testCaseName(name).data(data).build();
        ResponseEntity<TestCaseResponseDto> res = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private DeploymentReferenceDto buildDeploymentRef() {
        return DeploymentReferenceDto.builder()
                .id("deployment-1")
                .name("D1")
                .version("v1")
                .build();
    }

    private TestSuiteResponseDto createSuiteWithDeploymentIdAndUrl(String deploymentId, String urlTemplate) {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate(urlTemplate)
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}"))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Encoded Id Suite " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id(deploymentId)
                        .name("D1")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern(urlTemplate)
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("col1")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("col1")
                        .build()))
                .build();
        ResponseEntity<TestSuiteResponseDto> res =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private EndpointContractDto buildEndpoint() {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/chat/completions")
                .build();
    }
}
