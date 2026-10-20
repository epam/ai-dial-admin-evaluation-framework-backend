package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixtures for array-based multi-turn functional tests: a chat-completions suite (optionally
 * carrying a {@code testCaseFilter}) bound to a {@code prompt}/{@code category} dataset, single- and
 * multi-turn case authoring, and run-creation with polling to a terminal status against a mocked
 * deployment. Reused by {@link MultiTurnRunFunctionalTests} and {@link MultiTurnFilterFunctionalTests}.
 */
public abstract class AbstractMultiTurnFunctionalTest extends BaseFunctionalTest {

    @Autowired
    protected DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    protected AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    protected MetaTestDataHelper metaTestDataHelper;

    @Autowired
    protected ObjectMapper objectMapper;

    protected DeploymentInvocationResult chatReply(String content) {
        return new DeploymentInvocationResult(
                200,
                false,
                Map.of(
                        "id",
                        "mock",
                        "choices",
                        List.of(Map.of("message", Map.of("role", "assistant", "content", content)))),
                null,
                new HttpHeaders());
    }

    protected TestSuiteRunResponseDto createRunAndAwaitTerminal(UUID suiteId, int timeoutSeconds) {
        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return awaitRunTerminal(response.getBody().getId(), timeoutSeconds);
    }

    protected TestSuiteRunResponseDto awaitRunTerminal(UUID runId, int timeoutSeconds) {
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

    protected TestCaseResponseDto createMultiTurnCase(UUID datasetId, String name, List<Map<String, Object>> turns) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .multiTurnData(turns)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /** Creates a multi-turn case carrying both shared (test-case-level) {@code data} and per-turn {@code turns}. */
    protected TestCaseResponseDto createMultiTurnCase(
            UUID datasetId, String name, Map<String, Object> sharedData, List<Map<String, Object>> turns) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(sharedData)
                        .multiTurnData(turns)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    protected TestCaseResponseDto createSingleTurnCase(UUID datasetId, String name, Map<String, Object> data) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(data)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    protected TestSuiteResponseDto createChatSuite(String name) {
        return createChatSuite(name, null);
    }

    /**
     * Creates a chat-completions DEPLOYMENT suite bound to a fresh dataset whose schema has a required
     * {@code prompt} and an optional {@code category}. When {@code testCaseFilter} is non-null it is
     * carried on the suite (validated at write time; applied at run time as ALL-turns-match).
     */
    protected TestSuiteResponseDto createChatSuite(String name, Map<String, Object> testCaseFilter) {
        return createChatSuite(name, testCaseFilter, List.of());
    }

    /**
     * As {@link #createChatSuite(String, Map)}, but appends {@code extraFields} to the dataset schema — for
     * filters that need a per-turn field of another type (e.g. an {@code ARRAY}) alongside
     * {@code prompt}/{@code category}.
     */
    protected TestSuiteResponseDto createChatSuite(
            String name, Map<String, Object> testCaseFilter, List<FieldDefinitionDto> extraFields) {
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
                .datasetId(newDatasetWithSchema(Stream.concat(
                                Stream.of(
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
                                                .build()),
                                extraFields.stream())
                        .toList()))
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
                .testCaseFilter(testCaseFilter)
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    protected FileMetadataDto uploadDatasetFile(UUID datasetId, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("pdf".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<FileMetadataDto> uploaded = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/files"), new HttpEntity<>(body, headers), FileMetadataDto.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return uploaded.getBody();
    }

    /**
     * Chat suite whose single message carries two hintless {@code ${{file}}}/{@code ${{file2}}}
     * attachment URLs bound to optional {@code FILE} fields of a dataset that also has a required
     * {@code message} STRING field. When {@code singleFileField} is true the schema declares only
     * {@code file} and both {@code file} and {@code file2} placeholders bind to it.
     */
    protected TestSuiteResponseDto createTwoFileAttachmentSuite(boolean singleFileField) {
        final String file2Field = singleFileField ? "file" : "file2";
        final List<FieldDefinitionDto> schema = new ArrayList<>(List.of(
                FieldDefinitionDto.builder()
                        .name("message")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("file")
                        .type(SchemaFieldType.FILE)
                        .required(false)
                        .build()));
        if (!singleFileField) {
            schema.add(FieldDefinitionDto.builder()
                    .name("file2")
                    .type(SchemaFieldType.FILE)
                    .required(false)
                    .build());
        }
        final UUID datasetId = newDatasetWithSchema(schema);
        final Map<String, Object> message = Map.of(
                "role",
                "user",
                "content",
                "${{message}}",
                "custom_content",
                Map.of("attachments", List.of(Map.of("url", "${{file}}"), Map.of("url", "${{file2}}"))));
        final TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Two FILE attachments " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(datasetId)
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("messages", List.of(message)))
                                .build())
                        .build())
                .inputBindings(List.of(
                        InputBindingDto.builder()
                                .templateVariable("message")
                                .dataField("message")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("file")
                                .dataField("file")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("file2")
                                .dataField(file2Field)
                                .build()))
                .build();
        final ResponseEntity<TestSuiteResponseDto> created =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        final TestSuiteResponseDto suite = created.getBody();
        assertThat(suite).isNotNull();
        assertThat(suite.isValid()).isTrue();
        return suite;
    }

    protected static String dialRef(FileMetadataDto file) {
        return "files/test-bucket" + file.getPath().substring(file.getPath().indexOf("/datasets/"));
    }

    /**
     * Chat suite whose body carries one {@code attachment} placeholder (pass {@code "${{doc}}"} or
     * {@code "${{doc|file}}"}) bound to an optional {@code FILE} field named {@code document}.
     */
    protected TestSuiteResponseDto createFileAttachmentSuite(String placeholder) {
        final UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("document")
                .type(SchemaFieldType.FILE)
                .required(false)
                .build()));
        final TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("FILE attachment " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(datasetId)
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("attachment", placeholder))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("doc")
                        .dataField("document")
                        .build()))
                .build();
        final ResponseEntity<TestSuiteResponseDto> created =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().isValid()).isTrue();
        return created.getBody();
    }

    protected UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("mt-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    /**
     * Chat suite whose request-template body is authored as a JSONata source string that accumulates
     * history via the {@code $history} frame variable (bound from the previous turn's {@code history}
     * response column) instead of a hardcoded {@code messages}-array auto-accumulation. Turn 0 evaluates
     * with {@code $history} unbound (undefined-append). Reused by {@link MultiTurnRunFunctionalTests} and
     * {@link TryItOutFunctionalTests}.
     */
    protected TestSuiteResponseDto createHistoryAccumulatingChatSuite(String name) {
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
                        .perTurn(true)
                        .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .jsonataContent("{\"messages\": $append($history, "
                                        + "[{\"role\": \"user\", \"content\": \"${{prompt}}\"}])}")
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("history")
                        .expression("$append($_request.messages, [$_response.choices[0].message])")
                        .type(SchemaFieldType.ARRAY)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
