package com.epam.aidial.evaluation.cli.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class SuiteContractValidatorTest {

    private final SuiteContractValidator validator = new SuiteContractValidator();

    @Test
    @DisplayName("passes for a suite with a well-formed endpointRef and requestTemplate")
    void passesForWellFormedSuite() {
        final TestSuiteResponseDto suite = suite(
                EndpointContractDto.builder().method(HttpMethod.POST).build(),
                RequestTemplateDto.builder().urlTemplate("/chat/completions").build());

        assertThatCode(() -> validator.validate(suite)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("throws when endpointRef is missing")
    void throwsWhenEndpointRefMissing() {
        final TestSuiteResponseDto suite = suite(
                null,
                RequestTemplateDto.builder().urlTemplate("/chat/completions").build());

        assertThatThrownBy(() -> validator.validate(suite))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpointRef is missing");
    }

    @Test
    @DisplayName("throws when endpointRef.method is missing")
    void throwsWhenEndpointRefMethodMissing() {
        final TestSuiteResponseDto suite = suite(
                EndpointContractDto.builder().build(),
                RequestTemplateDto.builder().urlTemplate("/chat/completions").build());

        assertThatThrownBy(() -> validator.validate(suite))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpointRef.method is missing");
    }

    @Test
    @DisplayName("throws when requestTemplate is missing")
    void throwsWhenRequestTemplateMissing() {
        final TestSuiteResponseDto suite =
                suite(EndpointContractDto.builder().method(HttpMethod.POST).build(), null);

        assertThatThrownBy(() -> validator.validate(suite))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requestTemplate is missing");
    }

    @Test
    @DisplayName("throws when requestTemplate.urlTemplate is blank")
    void throwsWhenUrlTemplateBlank() {
        final TestSuiteResponseDto suite = suite(
                EndpointContractDto.builder().method(HttpMethod.POST).build(),
                RequestTemplateDto.builder().urlTemplate("  ").build());

        assertThatThrownBy(() -> validator.validate(suite))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("urlTemplate is missing or blank");
    }

    @Test
    @DisplayName("does not throw on a request-body vs endpoint-schema content-type mismatch (warning only)")
    void doesNotThrowOnContentTypeMismatch() {
        final TestSuiteResponseDto suite = suite(
                EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .requestBodySchema(
                                MultipartFormDataRequestBodySchemaDto.builder().build())
                        .build(),
                RequestTemplateDto.builder()
                        .urlTemplate("/chat/completions")
                        .body(JsonRequestBodyDto.builder().build())
                        .build());

        assertThatCode(() -> validator.validate(suite)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("does not throw when both content types match")
    void doesNotThrowWhenContentTypesMatch() {
        final TestSuiteResponseDto suite = suite(
                EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .requestBodySchema(JsonRequestBodySchemaDto.builder().build())
                        .build(),
                RequestTemplateDto.builder()
                        .urlTemplate("/chat/completions")
                        .body(JsonRequestBodyDto.builder().build())
                        .build());

        assertThatCode(() -> validator.validate(suite)).doesNotThrowAnyException();
    }

    private TestSuiteResponseDto suite(EndpointContractDto endpointRef, RequestTemplateDto requestTemplate) {
        return TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .name("Suite")
                .endpointRef(endpointRef)
                .requestTemplate(requestTemplate)
                .build();
    }
}
