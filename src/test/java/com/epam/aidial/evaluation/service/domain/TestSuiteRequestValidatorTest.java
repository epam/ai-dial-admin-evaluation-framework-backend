package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.service.DashjoinJsonataEvaluationService;
import com.epam.aidial.evaluation.runner.service.JsonataEvaluationService;
import com.epam.aidial.evaluation.runner.service.JsonataSourcePreprocessor;
import com.epam.aidial.evaluation.runner.service.TemplateVariableResolver;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteRequestValidator Tests")
class TestSuiteRequestValidatorTest {

    private TestSuiteRequestValidator validator;

    @BeforeEach
    void setUp() {
        JsonataProperties jsonataProperties = new JsonataProperties();
        jsonataProperties.setEvaluationTimeoutMs(5000L);
        jsonataProperties.setMaxRecursionDepth(500);
        JsonataEvaluationService jsonataEvaluationService =
                new DashjoinJsonataEvaluationService(new ObjectMapper(), jsonataProperties);
        ValidationProperties validationProperties = new ValidationProperties();
        validationProperties.setMaxTemplateSizeBytes(65536);
        validationProperties.setMaxBindingsCount(64);
        JsonataSourcePreprocessor jsonataSourcePreprocessor = new JsonataSourcePreprocessor(
                mock(TemplateVariableResolver.class), mock(DialFileRefResolver.class), new ObjectMapper());
        validator = new TestSuiteRequestValidator(
                jsonataEvaluationService,
                jsonataSourcePreprocessor,
                mock(SchemaValidationService.class),
                new ObjectMapper(),
                validationProperties);
    }

    // --- requestTemplate.body.jsonataContent JSONata String validation ---

    @Test
    @DisplayName("Invalid JSONata jsonataContent request body is rejected")
    void shouldRejectInvalidJsonataContentBody() {
        TestSuiteRequestDto dto = requestWithJsonataBodyContent("choices[0.message.content");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTemplate.body.jsonataContent")
                .hasMessageContaining("Invalid JSONata expression");
    }

    @Test
    @DisplayName("Valid JSONata jsonataContent request body is accepted")
    void shouldAcceptValidJsonataContentBody() {
        TestSuiteRequestDto dto = requestWithJsonataBodyContent(
                "{\"messages\": $append($history, [{\"role\": \"user\", \"content\": \"${{q}}\"}])}");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Bare placeholder in object value position is accepted (not valid JSONata until substituted)")
    void shouldAcceptBarePlaceholderInValuePosition() {
        TestSuiteRequestDto dto = requestWithJsonataBodyContent("{\"q\": ${{question}}}");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Bare placeholder as a function argument is accepted")
    void shouldAcceptBarePlaceholderAsFunctionArgument() {
        TestSuiteRequestDto dto = requestWithJsonataBodyContent("$append($history, ${{messages}})");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Quoted full-value placeholder is still accepted")
    void shouldAcceptQuotedFullValuePlaceholder() {
        TestSuiteRequestDto dto = requestWithJsonataBodyContent("{\"q\": \"${{question}}\"}");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Genuinely invalid JSONata (unbalanced brackets) is still rejected")
    void shouldRejectGenuinelyInvalidJsonataAlongsideBarePlaceholder() {
        TestSuiteRequestDto dto = requestWithJsonataBodyContent("{\"a\": [1,2}");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTemplate.body.jsonataContent");
    }

    @Test
    @DisplayName("A placeholder followed by invalid syntax is still rejected")
    void shouldRejectBarePlaceholderFollowedByInvalidSyntax() {
        TestSuiteRequestDto dto = requestWithJsonataBodyContent("{\"q\": ${{question}} +}");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTemplate.body.jsonataContent");
    }

    // --- content / jsonataContent mutual exclusivity ---

    @Test
    @DisplayName("Both content and jsonataContent set is rejected")
    void shouldRejectBothContentAndJsonataContentSet() {
        TestSuiteRequestDto dto = requestWithBothBodyFields(
                Map.of("a", 1), "{\"messages\": $append($history, [{\"role\": \"user\", \"content\": \"x\"}])}");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTemplate.body: content and jsonataContent are mutually exclusive");
    }

    @Test
    @DisplayName("Map content is not JSONata-validated at write time")
    void shouldAcceptMapContentWithoutJsonataValidation() {
        // "choices[0.message.content" is invalid JSONata syntax, but here it is a plain Map value —
        // content is only structurally resolved and JSONata-evaluated at run time, never validated at write time.
        TestSuiteRequestDto dto = requestWithMapBodyContent(Map.of("expression", "choices[0.message.content"));

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Null request body content is accepted (no body)")
    void shouldAcceptNullBodyContent() {
        TestSuiteRequestDto dto = requestWithMapBodyContent(null);

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("No requestTemplate at all is accepted")
    void shouldAcceptNullRequestTemplate() {
        TestSuiteRequestDto dto = TestSuiteRequestDto.builder().name("Suite").build();

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    // --- responseColumns reserved-name validation ---

    @Test
    @DisplayName("Response column name colliding with JSONata built-in function name is rejected")
    void shouldRejectResponseColumnNameCollidingWithBuiltInFunction() {
        TestSuiteRequestDto dto = requestWithResponseColumn("count", "usage.total_tokens");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("count")
                .hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("Response column name colliding with reserved frame variable 'request' is rejected")
    void shouldRejectResponseColumnNameCollidingWithRequestFrameVariable() {
        TestSuiteRequestDto dto = requestWithResponseColumn("request", "usage.total_tokens");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("request")
                .hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("Non-colliding response column name is accepted")
    void shouldAcceptNonCollidingResponseColumnName() {
        TestSuiteRequestDto dto = requestWithResponseColumn("answer", "choices[0].message.content");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    private TestSuiteRequestDto requestWithMapBodyContent(Map<String, Object> content) {
        return TestSuiteRequestDto.builder()
                .name("Suite")
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder().content(content).build())
                        .build())
                .build();
    }

    private TestSuiteRequestDto requestWithJsonataBodyContent(String jsonataContent) {
        return TestSuiteRequestDto.builder()
                .name("Suite")
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .jsonataContent(jsonataContent)
                                .build())
                        .build())
                .build();
    }

    private TestSuiteRequestDto requestWithBothBodyFields(Map<String, Object> content, String jsonataContent) {
        return TestSuiteRequestDto.builder()
                .name("Suite")
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(content)
                                .jsonataContent(jsonataContent)
                                .build())
                        .build())
                .build();
    }

    private TestSuiteRequestDto requestWithResponseColumn(String name, String expression) {
        return TestSuiteRequestDto.builder()
                .name("Suite")
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name(name)
                        .expression(expression)
                        .build()))
                .build();
    }
}
