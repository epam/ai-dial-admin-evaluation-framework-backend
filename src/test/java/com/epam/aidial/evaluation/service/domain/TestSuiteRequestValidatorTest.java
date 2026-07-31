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

    // --- requestTemplate.body.content JSONata String validation ---

    @Test
    @DisplayName("Invalid JSONata String-content request body is rejected")
    void shouldRejectInvalidJsonataStringContentBody() {
        TestSuiteRequestDto dto = requestWithBodyContent("choices[0.message.content");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTemplate.body.content")
                .hasMessageContaining("Invalid JSONata expression");
    }

    @Test
    @DisplayName("Valid JSONata String-content request body is accepted")
    void shouldAcceptValidJsonataStringContentBody() {
        TestSuiteRequestDto dto = requestWithBodyContent(
                "{\"messages\": $append($history, [{\"role\": \"user\", \"content\": \"${{q}}\"}])}");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Bare placeholder in object value position is accepted (not valid JSONata until substituted)")
    void shouldAcceptBarePlaceholderInValuePosition() {
        TestSuiteRequestDto dto = requestWithBodyContent("{\"q\": ${{question}}}");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Bare placeholder as a function argument is accepted")
    void shouldAcceptBarePlaceholderAsFunctionArgument() {
        TestSuiteRequestDto dto = requestWithBodyContent("$append($history, ${{messages}})");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Quoted full-value placeholder is still accepted")
    void shouldAcceptQuotedFullValuePlaceholder() {
        TestSuiteRequestDto dto = requestWithBodyContent("{\"q\": \"${{question}}\"}");

        assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Genuinely invalid JSONata (unbalanced brackets) is still rejected")
    void shouldRejectGenuinelyInvalidJsonataAlongsideBarePlaceholder() {
        TestSuiteRequestDto dto = requestWithBodyContent("{\"a\": [1,2}");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTemplate.body.content");
    }

    @Test
    @DisplayName("A placeholder followed by invalid syntax is still rejected")
    void shouldRejectBarePlaceholderFollowedByInvalidSyntax() {
        TestSuiteRequestDto dto = requestWithBodyContent("{\"q\": ${{question}} +}");

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTemplate.body.content");
    }

    @Test
    @DisplayName("Non-Map, non-String request body content is rejected")
    void shouldRejectNonObjectNonStringContent() {
        TestSuiteRequestDto dto = requestWithBodyContent(42);

        assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestTemplate.body.content")
                .hasMessageContaining("must be a JSON object or a JSONata source string");
    }

    @Test
    @DisplayName("Null request body content is accepted (no body)")
    void shouldAcceptNullBodyContent() {
        TestSuiteRequestDto dto = requestWithBodyContent(null);

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

    private TestSuiteRequestDto requestWithBodyContent(Object content) {
        return TestSuiteRequestDto.builder()
                .name("Suite")
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder().content(content).build())
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
