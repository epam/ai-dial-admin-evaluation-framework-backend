package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartSchemaDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Evaluation with Multipart Template Functional Tests")
public abstract class EvaluationMultipartFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("emp-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    // --- Task 14.3: evaluation run with multipart template (end-to-end) ---

    @Test
    @DisplayName("14.3 Evaluation run with multipart template completes successfully")
    void evaluationRunWithMultipartTemplateCompletes() {
        // Create suite with multipart template
        TestSuiteResponseDto suite = createSuiteWithMultipartTemplate();

        // Create a test case
        TestCaseResponseDto tc =
                createTestCase(suite.getId(), "Multipart Eval TC", Map.of("userPrompt", "Evaluate this"));

        // Mock the deployment invoker
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200, false, Map.of("result", "evaluated"), null, new HttpHeaders()));

        // Start a run
        ResponseEntity<TestSuiteRunResponseDto> runResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder()
                                .numberOfRuns(1)
                                .testRunName("multipart-eval-" + UUID.randomUUID())
                                .build())
                        .build()),
                TestSuiteRunResponseDto.class);

        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(runResponse.getBody()).isNotNull();
        UUID runId = runResponse.getBody().getId();

        // Poll until terminal state
        TestSuiteRunResponseDto terminalRun = awaitRunTerminal(runId, 30);

        assertThat(terminalRun).isNotNull();
        assertThat(terminalRun.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(terminalRun.getNumberOfTestCases()).isEqualTo(1);
    }

    // --- Helpers ---

    private TestSuiteResponseDto createSuiteWithMultipartTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/process")
                .body(MultipartFormDataRequestBodyDto.builder()
                        .content(List.of(FormPartDto.builder()
                                .name("prompt")
                                .type(FormPartType.TEXT)
                                .value("${{userPrompt}}")
                                .build()))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Eval Multipart Suite " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("d1")
                        .name("D1")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/process")
                        .requestBodySchema(MultipartFormDataRequestBodySchemaDto.builder()
                                .parts(List.of(FormPartSchemaDto.builder()
                                        .name("prompt")
                                        .type(FormPartType.TEXT)
                                        .required(true)
                                        .build()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("userPrompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("userPrompt")
                        .dataField("userPrompt")
                        .build()))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestCaseResponseDto createTestCase(UUID suiteId, String name, Map<String, Object> data) {
        TestCaseRequestDto req =
                TestCaseRequestDto.builder().testCaseName(name).data(data).build();
        ResponseEntity<TestCaseResponseDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases"),
                jsonEntity(req),
                TestCaseResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
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
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling", e);
            }
        }
        throw new AssertionError("Run did not complete within " + timeoutSeconds + " seconds");
    }
}
