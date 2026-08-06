package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ParameterLocation;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.util.ArrayList;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Max Limits Functional Tests (template size, bindings count, duplicate templateVariable)")
public abstract class MaxLimitsFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    // --- Suite-level limits ---

    @Test
    @DisplayName("Should reject suite creation when requestTemplate exceeds max size")
    void shouldRejectSuiteWithOversizedTemplate() {
        // Build a template with a body larger than 64KB
        Map<String, Object> largeBody = new HashMap<>();
        String largeValue = "x".repeat(70000);
        largeBody.put("bigField", largeValue);

        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder().content(largeBody).build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Oversized Template Suite " + UUID.randomUUID())
                .description("Should fail")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newEmptyDatasetId())
                .requestTemplate(template)
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("requestTemplate");
        assertThat(response.getBody()).contains("exceeds maximum size");
    }

    @Test
    @DisplayName("Should reject suite creation when inputBindings count exceeds max")
    void shouldRejectSuiteWithTooManyBindings() {
        List<InputBindingDto> manyBindings = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            manyBindings.add(InputBindingDto.builder()
                    .templateVariable("var" + i)
                    .dataField("field" + i)
                    .build());
        }

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Too Many Bindings Suite " + UUID.randomUUID())
                .description("Should fail")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newEmptyDatasetId())
                .inputBindings(manyBindings)
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("inputBindings");
        assertThat(response.getBody()).contains("exceeds maximum");
    }

    @Test
    @DisplayName("Should reject suite creation when inputBindings has duplicate templateVariable")
    void shouldRejectSuiteWithDuplicateTemplateVariable() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Duplicate Binding Suite " + UUID.randomUUID())
                .description("Should fail")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newEmptyDatasetId())
                .inputBindings(List.of(
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("field1")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("field2")
                                .build()))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Duplicate");
        assertThat(response.getBody()).contains("prompt");
    }

    @Test
    @DisplayName("Should accept suite with bindings count at the limit")
    void shouldAcceptSuiteWithBindingsAtLimit() {
        List<InputBindingDto> bindings = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            bindings.add(InputBindingDto.builder()
                    .templateVariable("var" + i)
                    .dataField("field" + i)
                    .build());
        }

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Bindings At Limit Suite " + UUID.randomUUID())
                .description("Should succeed")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newEmptyDatasetId())
                .inputBindings(bindings)
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // --- Mutual exclusivity validation (dataField vs constantValue) ---

    @Test
    @DisplayName("Should reject suite creation when binding has both dataField and constantValue")
    void shouldRejectSuiteWithBindingHavingBothFields() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Both Fields Suite " + UUID.randomUUID())
                .description("Should fail - both dataField and constantValue set")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newEmptyDatasetId())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("var1")
                        .dataField("field1")
                        .constantValue("constant1")
                        .build()))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Exactly one of dataField or constantValue must be set");
    }

    @Test
    @DisplayName("Should reject suite creation when binding has neither dataField nor constantValue")
    void shouldRejectSuiteWithBindingHavingNeitherField() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Neither Field Suite " + UUID.randomUUID())
                .description("Should fail - neither dataField nor constantValue set")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newEmptyDatasetId())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("var1")
                        // both dataField and constantValue are null
                        .build()))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Exactly one of dataField or constantValue must be set");
    }

    @Test
    @DisplayName("Should accept suite with binding having only dataField")
    void shouldAcceptSuiteWithBindingHavingOnlyDataField() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Only DataField Suite " + UUID.randomUUID())
                .description("Should succeed")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("field1")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("var1")
                        .dataField("field1")
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Should accept suite with binding having only constantValue")
    void shouldAcceptSuiteWithBindingHavingOnlyConstantValue() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Only ConstantValue Suite " + UUID.randomUUID())
                .description("Should succeed")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newEmptyDatasetId())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("var1")
                        .constantValue("constant1")
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // --- Helpers ---

    private UUID newEmptyDatasetId() {
        Dataset dataset = metaTestDataHelper.createDataset("limits-" + UUID.randomUUID());
        return dataset.getId();
    }

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("limits-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private DeploymentReferenceDto buildDeploymentRef() {
        return DeploymentReferenceDto.builder()
                .id("d1")
                .name("D1")
                .version("v1")
                .build();
    }

    private EndpointContractDto buildEndpoint() {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/v1/chat")
                .parameters(List.of(ParameterDefinitionDto.builder()
                        .name("query")
                        .in(ParameterLocation.QUERY)
                        .required(true)
                        .schema(Map.of("type", "string"))
                        .build()))
                .requestBodySchema(JsonRequestBodySchemaDto.builder()
                        .schema(Map.of("type", "object", "properties", Map.of()))
                        .build())
                .build();
    }
}
