package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ParameterLocation;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
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

@DisplayName("Header Blacklist Functional Tests")
public abstract class HeaderBlacklistFunctionalTests extends BaseFunctionalTest {

    @Autowired
    protected MetaTestDataHelper metaTestDataHelper;

    @Autowired
    protected ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("hb-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("Should mark suite invalid when blacklisted header is present")
    void shouldMarkSuiteInvalidWhenBlacklistedHeaderPresent() {
        TestSuiteRequestDto request = buildSuiteRequest(
                "Suite With Blacklisted Header",
                List.of(
                        KeyValueTemplateDto.builder()
                                .key("Authorization")
                                .value("Bearer token")
                                .build(),
                        KeyValueTemplateDto.builder()
                                .key("X-Custom")
                                .value("value")
                                .build()));

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isFalse();
        assertThat(response.getBody().getValidationWarnings()).isNotEmpty();
        assertThat(response.getBody().getValidationWarnings())
                .anyMatch(w -> w.getFieldName() != null
                        && w.getFieldName().equalsIgnoreCase("Authorization")
                        && w.getMessage().contains("system-managed"));
    }

    @Test
    @DisplayName("Should pass validation when no blacklisted headers are present")
    void shouldPassValidationWhenNoBlacklistedHeaders() {
        TestSuiteRequestDto request = buildSuiteRequest(
                "Suite With Custom Headers",
                List.of(
                        KeyValueTemplateDto.builder()
                                .key("X-Custom-Header")
                                .value("custom-value")
                                .build(),
                        KeyValueTemplateDto.builder()
                                .key("Accept")
                                .value("application/json")
                                .build()));

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertSuiteConfigValid(response.getBody());
    }

    // --- Helper Methods ---

    private TestSuiteRequestDto buildSuiteRequest(String name, List<KeyValueTemplateDto> headers) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .parameters(List.of(ParameterDefinitionDto.builder()
                                .name("query")
                                .in(ParameterLocation.QUERY)
                                .required(true)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of(
                                        "type", "object",
                                        "required", List.of("prompt"),
                                        "properties", Map.of("prompt", Map.of("type", "string"))))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .headers(headers)
                        .build())
                .build();
    }
}
