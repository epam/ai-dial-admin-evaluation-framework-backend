package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartSchemaDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedUrlEncodedBodyDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.UrlEncodedFormRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.UrlEncodedFormRequestBodySchemaDto;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Polymorphic Body Functional Tests")
public abstract class PolymorphicBodyFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            return metaTestDataHelper
                    .createDataset("polybody-" + UUID.randomUUID(), schemaJson)
                    .getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    // --- Task 12.3: suite validation with multipart template and matching schema ---

    @Test
    @DisplayName("12.3 Suite with multipart template and matching multipart schema is valid")
    void suiteWithMultipartTemplateAndMatchingSchemaIsValid() {
        TestSuiteResponseDto suite = createSuiteWithMultipartTemplateAndSchema();

        assertThat(suite).isNotNull();
        assertSuiteConfigValid(suite);
        // No content-type mismatch warning
        if (suite.getValidationWarnings() != null) {
            assertThat(suite.getValidationWarnings().stream()
                            .noneMatch(w -> w.getMessage().toLowerCase().contains("content-type mismatch")))
                    .isTrue();
        }
    }

    // --- Task 12.4: suite validation with content-type mismatch warning ---

    @Test
    @DisplayName("12.4 Suite with multipart template but JSON schema produces content-type mismatch warning")
    void suiteWithContentTypeMismatchProducesWarning() {
        // Create suite: template uses multipart/form-data, schema uses application/json
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/upload")
                .body(MultipartFormDataRequestBodyDto.builder()
                        .content(List.of(FormPartDto.builder()
                                .name("text")
                                .type(FormPartType.TEXT)
                                .value("${{prompt}}")
                                .build()))
                        .build())
                .build();

        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Mismatch Suite " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("d1")
                        .name("D1")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/upload")
                        // JSON schema with multipart template = mismatch
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        TestSuiteResponseDto suite = response.getBody();

        // Should have a content-type mismatch validation warning
        assertThat(suite.getValidationWarnings()).isNotNull();
        assertThat(suite.getValidationWarnings()).isNotEmpty();
        assertThat(suite.getValidationWarnings().stream()
                        .anyMatch(w -> w.getMessage().toLowerCase().contains("content type")
                                && w.getMessage().toLowerCase().contains("does not match")))
                .isTrue();
    }

    // --- Task 6.6 / 13.3: try-it-out with JSON body (regression) ---

    @Test
    @DisplayName("6.6/13.3 Try-it-out with JSON body returns resolved JSON body (regression)")
    void tryItOutWithJsonBodyReturnsResolvedJsonBody() {
        TestSuiteResponseDto suite = createSuiteWithJsonTemplate();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "JSON TC", Map.of("prompt", "Hello JSON"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(
                        new DeploymentInvocationResult(200, false, Map.of("response", "ok"), null, new HttpHeaders()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResolvedRequest()).isNotNull();

        ResolvedBodyDto body = response.getBody().getResolvedRequest().getBody();
        assertThat(body).isInstanceOf(ResolvedJsonBodyDto.class);
        ResolvedJsonBodyDto jsonBody = (ResolvedJsonBodyDto) body;
        assertThat(jsonBody.getContent()).containsEntry("prompt", "Hello JSON");
        assertThat(response.getBody().getResponse()).isNotNull();
        assertThat(response.getBody().getResponse().getStatusCode()).isEqualTo(200);
    }

    // --- Task 13.4: try-it-out with multipart template ---

    @Test
    @DisplayName("13.4 Try-it-out with multipart template returns resolved multipart body")
    void tryItOutWithMultipartTemplateReturnsResolvedMultipartBody() {
        TestSuiteResponseDto suite = createSuiteWithMultipartTemplateAndSchema();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "Multipart TC", Map.of("prompt", "Hello multipart"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(
                        new DeploymentInvocationResult(200, false, Map.of("result", "ok"), null, new HttpHeaders()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResolvedRequest()).isNotNull();

        ResolvedBodyDto body = response.getBody().getResolvedRequest().getBody();
        assertThat(body).isInstanceOf(ResolvedMultipartBodyDto.class);
        ResolvedMultipartBodyDto multipartBody = (ResolvedMultipartBodyDto) body;
        assertThat(multipartBody.getParts()).isNotEmpty();
        assertThat(multipartBody.getParts().get(0).getName()).isEqualTo("text");
        assertThat(multipartBody.getParts().get(0).getResolvedValue()).isEqualTo("Hello multipart");
    }

    // --- Task 13.5: try-it-out with URL-encoded template ---

    @Test
    @DisplayName("13.5 Try-it-out with URL-encoded template returns resolved URL-encoded body")
    void tryItOutWithUrlEncodedTemplateReturnsResolvedUrlEncodedBody() {
        TestSuiteResponseDto suite = createSuiteWithUrlEncodedTemplate();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "URL-encoded TC", Map.of("query", "search term"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(
                        new DeploymentInvocationResult(200, false, Map.of("result", "found"), null, new HttpHeaders()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResolvedRequest()).isNotNull();

        ResolvedBodyDto body = response.getBody().getResolvedRequest().getBody();
        assertThat(body).isInstanceOf(ResolvedUrlEncodedBodyDto.class);
        ResolvedUrlEncodedBodyDto urlEncodedBody = (ResolvedUrlEncodedBodyDto) body;
        assertThat(urlEncodedBody.getEntries()).isNotEmpty();
        assertThat(urlEncodedBody.getEntries().stream()
                        .anyMatch(e -> "q".equals(e.getKey()) && "search term".equals(e.getValue())))
                .isTrue();
    }

    // --- Suite-level try-it-out with variables ---

    @Test
    @DisplayName("13.3b Try-it-out with variables on JSON suite returns resolved JSON body")
    void tryItOutWithVariablesReturnsResolvedJsonBody() {
        TestSuiteResponseDto suite = createSuiteWithJsonTemplate();

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(
                        new DeploymentInvocationResult(200, false, Map.of("reply", "done"), null, new HttpHeaders()));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("prompt", "Variable prompt"))
                .build();

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"),
                jsonEntity(request),
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        ResolvedBodyDto body = response.getBody().getResolvedRequest().getBody();
        assertThat(body).isInstanceOf(ResolvedJsonBodyDto.class);
        assertThat(((ResolvedJsonBodyDto) body).getContent()).containsEntry("prompt", "Variable prompt");
    }

    // --- Helpers ---

    private TestSuiteResponseDto createSuiteWithJsonTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/chat/completions")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}", "temperature", "${{temperature:0.7}}"))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("JSON Suite " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("d1")
                        .name("D1")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/chat/completions")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestSuiteResponseDto createSuiteWithMultipartTemplateAndSchema() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/upload")
                .body(MultipartFormDataRequestBodyDto.builder()
                        .content(List.of(FormPartDto.builder()
                                .name("text")
                                .type(FormPartType.TEXT)
                                .value("${{prompt}}")
                                .build()))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Multipart Suite " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("d1")
                        .name("D1")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/upload")
                        .requestBodySchema(MultipartFormDataRequestBodySchemaDto.builder()
                                .parts(List.of(FormPartSchemaDto.builder()
                                        .name("text")
                                        .type(FormPartType.TEXT)
                                        .required(true)
                                        .build()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestSuiteResponseDto createSuiteWithUrlEncodedTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/search")
                .body(UrlEncodedFormRequestBodyDto.builder()
                        .content(List.of(
                                KeyValueTemplateDto.builder()
                                        .key("q")
                                        .value("${{query}}")
                                        .build(),
                                KeyValueTemplateDto.builder()
                                        .key("limit")
                                        .value("10")
                                        .build()))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("URL-encoded Suite " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("d1")
                        .name("D1")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/search")
                        .requestBodySchema(UrlEncodedFormRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("query")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("query")
                        .dataField("query")
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
}
