package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutWithVariablesRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Try It Out Functional Tests")
public abstract class TryItOutFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            return metaTestDataHelper
                    .createDataset("tryitout-" + UUID.randomUUID(), schemaJson)
                    .getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

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

    // --- Helpers ---

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
