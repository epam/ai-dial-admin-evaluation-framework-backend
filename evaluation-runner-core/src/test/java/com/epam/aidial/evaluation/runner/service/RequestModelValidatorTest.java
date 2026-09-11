package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.constants.ModelSelectingEndpointPaths;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.runner.exception.RequestBodyValidationException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RequestModelValidator")
class RequestModelValidatorTest {

    private static final String DEPLOYMENT_ID = "deployment-1";

    private final RequestModelValidator validator = new RequestModelValidator();

    @Test
    @DisplayName("matching string models pass for both canonical paths")
    void matchingModelsPassForBothCanonicalPaths() {
        final Map<String, Object> content = Map.of("model", DEPLOYMENT_ID);

        assertThat(validator.validateContent(ModelSelectingEndpointPaths.OPENAI_RESPONSES, content, DEPLOYMENT_ID))
                .isEmpty();
        assertThat(validator.validateContent(ModelSelectingEndpointPaths.ANTHROPIC_MESSAGES, content, DEPLOYMENT_ID))
                .isEmpty();
    }

    @Test
    @DisplayName("case variants and subpaths are outside exact validation applicability")
    void nearMissPathsAreNotValidated() {
        final Map<String, Object> invalidContent = Map.of();

        assertThat(validator.validateContent("/OPENAI/v1/responses", invalidContent, DEPLOYMENT_ID))
                .isEmpty();
        assertThat(validator.validateContent("/openai/v1/responses/resp-1", invalidContent, DEPLOYMENT_ID))
                .isEmpty();
        assertThat(validator.validateContent("/anthropic/v1/messages/count_tokens", invalidContent, DEPLOYMENT_ID))
                .isEmpty();
    }

    @Test
    @DisplayName("an absent resolved body fails on a canonical path")
    void absentResolvedBodyFails() {
        assertThatThrownBy(() -> validator.validateForExecution(
                        ModelSelectingEndpointPaths.OPENAI_RESPONSES, null, DEPLOYMENT_ID))
                .isInstanceOf(RequestBodyValidationException.class)
                .hasMessageContaining("JSON object")
                .hasMessageContaining(DEPLOYMENT_ID);
    }

    @Test
    @DisplayName("a non-JSON resolved body fails on a canonical path")
    void nonJsonResolvedBodyFails() {
        final ResolvedMultipartBodyDto body = new ResolvedMultipartBodyDto();

        assertThatThrownBy(() -> validator.validateForExecution(
                        ModelSelectingEndpointPaths.ANTHROPIC_MESSAGES, body, DEPLOYMENT_ID))
                .isInstanceOf(RequestBodyValidationException.class)
                .hasMessageContaining("JSON object")
                .hasMessageContaining(DEPLOYMENT_ID);
    }

    @Test
    @DisplayName("a missing model fails validation")
    void missingModelFails() {
        assertThat(validator.validateContent(
                        ModelSelectingEndpointPaths.OPENAI_RESPONSES, Map.of("input", "hello"), DEPLOYMENT_ID))
                .hasValueSatisfying(message -> assertThat(message).contains("model", "required", DEPLOYMENT_ID));
    }

    @Test
    @DisplayName("a null model fails validation")
    void nullModelFails() {
        final Map<String, Object> content = new HashMap<>();
        content.put("model", null);

        assertThat(validator.validateContent(ModelSelectingEndpointPaths.OPENAI_RESPONSES, content, DEPLOYMENT_ID))
                .hasValueSatisfying(message -> assertThat(message).contains("model", "non-null string", DEPLOYMENT_ID));
    }

    @Test
    @DisplayName("a non-string model fails validation")
    void nonStringModelFails() {
        assertThat(validator.validateContent(
                        ModelSelectingEndpointPaths.OPENAI_RESPONSES, Map.of("model", 42), DEPLOYMENT_ID))
                .hasValueSatisfying(message -> assertThat(message).contains("model", "string", DEPLOYMENT_ID));
    }

    @Test
    @DisplayName("a model containing an embedded placeholder fails using find semantics")
    void embeddedPlaceholderFails() {
        assertThat(validator.validateContent(
                        ModelSelectingEndpointPaths.OPENAI_RESPONSES,
                        Map.of("model", "prefix-${{deployment}}-suffix"),
                        DEPLOYMENT_ID))
                .hasValueSatisfying(message -> assertThat(message).contains("model", "${{...}}", DEPLOYMENT_ID));
    }

    @Test
    @DisplayName("a mismatched model fails validation")
    void mismatchedModelFails() {
        assertThat(validator.validateContent(
                        ModelSelectingEndpointPaths.ANTHROPIC_MESSAGES, Map.of("model", "other"), DEPLOYMENT_ID))
                .hasValueSatisfying(message -> assertThat(message).contains("model", "other", DEPLOYMENT_ID));
    }

    @Test
    @DisplayName("a matching resolved JSON model passes execution validation")
    void matchingResolvedModelPasses() {
        final ResolvedJsonBodyDto body = ResolvedJsonBodyDto.builder()
                .content(Map.of("model", DEPLOYMENT_ID))
                .build();

        assertThatCode(() -> validator.validateForExecution(
                        ModelSelectingEndpointPaths.OPENAI_RESPONSES, body, DEPLOYMENT_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an invalid body is ignored for a non-canonical resolved path")
    void invalidBodyIsIgnoredForNonCanonicalResolvedPath() {
        assertThatCode(() -> validator.validateForExecution("/chat/completions", null, DEPLOYMENT_ID))
                .doesNotThrowAnyException();
    }
}
