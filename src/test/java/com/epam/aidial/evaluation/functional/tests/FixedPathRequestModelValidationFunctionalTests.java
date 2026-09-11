package com.epam.aidial.evaluation.functional.tests;

import static com.epam.aidial.evaluation.runner.constants.ModelSelectingEndpointPaths.ANTHROPIC_MESSAGES;
import static com.epam.aidial.evaluation.runner.constants.ModelSelectingEndpointPaths.OPENAI_RESPONSES;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional coverage for the static half of {@code validate-request-model-deployment-id}: a plain
 * {@code content.model} that does not match {@code deploymentRef.id} on a fixed-path model-selecting
 * API is persisted as an invalid suite (soft validation), while matching and {@code jsonataContent}
 * bodies stay valid.
 */
@DisplayName("Fixed-path request-model validation — soft suite validation through the real pipeline")
public abstract class FixedPathRequestModelValidationFunctionalTests extends BaseFunctionalTest {

    private static final String DEPLOYMENT_ID = "deployment-1";

    @Test
    @DisplayName("Create persists an invalid suite with REQUEST_BODY_VALIDATION_ERROR for a mismatched model")
    void shouldPersistInvalidSuite_whenPlainModelMismatches() {
        TestSuiteRequestDto request = fixedPathSuite(
                "Model-mismatch-" + UUID.randomUUID(), ANTHROPIC_MESSAGES, Map.of("model", "other-deployment"));

        ResponseEntity<TestSuiteResponseDto> createResponse =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().isValid()).isFalse();
        assertThat(createResponse.getBody().getValidationWarnings()).anySatisfy(warning -> {
            assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR);
            assertThat(warning.getPath()).isEqualTo("$.requestTemplate.body");
            assertThat(warning.getMessage()).contains("other-deployment").contains(DEPLOYMENT_ID);
        });

        ResponseEntity<TestSuiteResponseDto> getResponse = restTemplate.getForEntity(
                apiUrl("/test-suites/" + createResponse.getBody().getId()), TestSuiteResponseDto.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().isValid()).isFalse();
        assertThat(getResponse.getBody().getValidationWarnings())
                .extracting(ValidationWarningDto::getCode)
                .contains(ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR);
    }

    @Test
    @DisplayName("Create keeps a suite valid when the plain model equals deploymentRef.id")
    void shouldPersistValidSuite_whenPlainModelMatches() {
        TestSuiteRequestDto request =
                fixedPathSuite("Model-match-" + UUID.randomUUID(), OPENAI_RESPONSES, Map.of("model", DEPLOYMENT_ID));

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getValidationWarnings())
                .extracting(ValidationWarningDto::getCode)
                .doesNotContain(ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR);
    }

    @Test
    @DisplayName("Create keeps a jsonataContent suite valid even when its expression names another model")
    void shouldPersistValidSuite_whenBodyIsJsonataContent() {
        TestSuiteRequestDto request = fixedPathSuite("Model-jsonata-" + UUID.randomUUID(), ANTHROPIC_MESSAGES, null);
        request.getRequestTemplate()
                .setBody(JsonRequestBodyDto.builder()
                        .jsonataContent("{ \"model\": \"other-deployment\", \"messages\": [] }")
                        .build());

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getValidationWarnings())
                .extracting(ValidationWarningDto::getCode)
                .doesNotContain(ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR);
    }

    @Test
    @DisplayName("Update to a body without a model makes the persisted suite invalid")
    void shouldPersistInvalidSuite_whenUpdateDropsModel() {
        String name = "Model-update-" + UUID.randomUUID();
        ResponseEntity<TestSuiteResponseDto> createResponse = restTemplate.postForEntity(
                apiUrl("/test-suites"),
                jsonEntity(fixedPathSuite(name, ANTHROPIC_MESSAGES, Map.of("model", DEPLOYMENT_ID))),
                TestSuiteResponseDto.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().isValid()).isTrue();

        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + createResponse.getBody().getVersion() + "\"");
        TestSuiteRequestDto updateRequest = fixedPathSuite(name, ANTHROPIC_MESSAGES, Map.of("messages", List.of()));

        ResponseEntity<TestSuiteResponseDto> updateResponse = restTemplate.exchange(
                apiUrl("/test-suites/" + createResponse.getBody().getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                TestSuiteResponseDto.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).isNotNull();
        assertThat(updateResponse.getBody().isValid()).isFalse();
        assertThat(updateResponse.getBody().getValidationWarnings()).anySatisfy(warning -> {
            assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR);
            assertThat(warning.getPath()).isEqualTo("$.requestTemplate.body");
        });
    }

    private TestSuiteRequestDto fixedPathSuite(String name, String relativeUrl, Map<String, Object> content) {
        RequestTemplateDto template =
                RequestTemplateDto.builder().urlTemplate(relativeUrl).build();
        if (content != null) {
            template.setBody(JsonRequestBodyDto.builder().content(content).build());
        }
        return TestSuiteRequestDto.builder()
                .name(name)
                .description("Fixed-path request-model validation fixture")
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id(DEPLOYMENT_ID)
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern(relativeUrl)
                        .build())
                .requestTemplate(template)
                .build();
    }
}
