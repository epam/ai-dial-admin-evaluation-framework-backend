package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end functional test for the multi-step conversation POC: create → run a multi-step suite against a
 * mocked DIAL Core deployment, asserting that the single persisted result reuses the existing columns
 * ({@code response_body} = accumulated messages, {@code extracted_columns} = per-step array).
 */
@DisplayName("Multi-step Conversation Run Functional Tests")
public abstract class MultiStepConversationRunFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Should persist accumulated messages and per-step extraction array for a 2-step conversation")
    void shouldRunTwoStepConversation() throws JacksonException {
        TestSuiteResponseDto suite = createMultiStepSuite();
        assertThat(suite.isMultiStep()).isTrue();
        assertThat(suite.isValid()).isTrue();
        createTestCase(suite.getId(), "TC1", Map.of("q1", "hello", "q2", "how are you"));

        // Each step returns a distinct assistant reply so we can verify per-step accumulation/extraction.
        AtomicInteger callCount = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int i = callCount.getAndIncrement();
                    return new DeploymentInvocationResult(
                            200,
                            false,
                            Map.of(
                                    "id",
                                    "mock",
                                    "choices",
                                    List.of(Map.of("message", Map.of("role", "assistant", "content", "reply-" + i)))),
                            null,
                            new HttpHeaders());
                });

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> row = results.get(0);
        assertThat(String.valueOf(row.get("execution_status"))).isEqualTo("SUCCESS");

        // Two steps → two deployment calls
        assertThat(callCount.get()).isEqualTo(2);

        // response_body = accumulated messages: [user(hello), assistant(reply-0), user(how are you),
        // assistant(reply-1)]
        JsonNode responseBody = objectMapper.readTree(String.valueOf(row.get("response_body")));
        assertThat(responseBody.isArray()).isTrue();
        assertThat(responseBody.size()).isEqualTo(4);
        assertThat(responseBody.get(0).get("role").asString()).isEqualTo("user");
        assertThat(responseBody.get(0).get("content").asString()).isEqualTo("hello");
        assertThat(responseBody.get(1).get("role").asString()).isEqualTo("assistant");
        assertThat(responseBody.get(1).get("content").asString()).isEqualTo("reply-0");
        assertThat(responseBody.get(2).get("content").asString()).isEqualTo("how are you");
        assertThat(responseBody.get(3).get("content").asString()).isEqualTo("reply-1");

        // extracted_columns = per-step array of length 2
        JsonNode extracted = objectMapper.readTree(String.valueOf(row.get("extracted_columns")));
        assertThat(extracted.isArray()).isTrue();
        assertThat(extracted.size()).isEqualTo(2);
        assertThat(extracted.get(0).get("answer").asString()).isEqualTo("reply-0");
        assertThat(extracted.get(1).get("answer").asString()).isEqualTo("reply-1");
    }

    private TestSuiteResponseDto createMultiStepSuite() throws JacksonException {
        String schemaJson = objectMapper.writeValueAsString(List.of(
                FieldDefinitionDto.builder()
                        .name("q1")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("q2")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()));
        Dataset dataset = metaTestDataHelper.createDataset("multistep-" + UUID.randomUUID(), schemaJson);

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Multi-step Suite " + UUID.randomUUID())
                .description("multi-step POC")
                .datasetId(dataset.getId())
                .multiStep(true)
                .multistepInputBindings(List.of(
                        List.of(InputBindingDto.builder()
                                .templateVariable("turn")
                                .dataField("q1")
                                .build()),
                        List.of(InputBindingDto.builder()
                                .templateVariable("turn")
                                .dataField("q2")
                                .build())))
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat/completions")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat/completions")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of(
                                        "model",
                                        "gpt-4",
                                        "messages",
                                        List.of(Map.of("role", "user", "content", "${{turn}}"))))
                                .build())
                        .build())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private TestCaseResponseDto createTestCase(UUID suiteId, String name, Map<String, Object> data) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(data)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteRunResponseDto createRunAndAwaitTerminal(UUID suiteId) {
        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return awaitRunTerminal(response.getBody().getId(), 30);
    }

    private TestSuiteRunResponseDto awaitRunTerminal(UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && RunStatus.isTerminal(get.getBody().getStatus())) {
                return get.getBody();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run", e);
            }
        }
        throw new AssertionError("Run did not reach terminal status within " + timeoutSeconds + "s");
    }
}
