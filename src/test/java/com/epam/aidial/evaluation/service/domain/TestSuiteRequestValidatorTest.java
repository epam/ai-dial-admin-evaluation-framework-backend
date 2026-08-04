package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.service.DashjoinJsonataEvaluationService;
import com.epam.aidial.evaluation.runner.service.JsonataEvaluationService;
import com.epam.aidial.evaluation.runner.service.JsonataSourcePreprocessor;
import com.epam.aidial.evaluation.runner.service.TemplateVariableResolver;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
        JsonbMapper jsonbMapper = new JsonbMapper(new ObjectMapper(), new RunnerJsonbMapper(new ObjectMapper()));
        validator = new TestSuiteRequestValidator(
                jsonataEvaluationService,
                jsonataSourcePreprocessor,
                mock(SchemaValidationService.class),
                new ObjectMapper(),
                validationProperties,
                new ResponseColumnUnionResolver(jsonbMapper));
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

    // --- request chain validation (additionalRequests) ---

    @Nested
    @DisplayName("Request chain validation")
    class RequestChainValidation {

        @Test
        @DisplayName("Duplicate response column name across request #0 and an additional request is rejected")
        void shouldRejectDuplicateNameAcrossChain() {
            TestSuiteRequestDto dto = chainRequestWithColumns(
                    List.of(column("answer", "choices[0].message.content")),
                    List.of(chainRequest("second", List.of(column("answer", "usage.total_tokens")))));

            assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("duplicate column name 'answer'")
                    .hasMessageContaining("additionalRequests[0]");
        }

        @Test
        @DisplayName("Duplicate response column name within one additional request is rejected")
        void shouldRejectDuplicateNameWithinOneAdditionalRequest() {
            TestSuiteRequestDto dto = chainRequestWithColumns(
                    List.of(),
                    List.of(chainRequest(
                            "second",
                            List.of(
                                    column("answer", "choices[0].message.content"),
                                    column("answer", "usage.total_tokens")))));

            assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("duplicate column name 'answer'");
        }

        @Test
        @DisplayName("A null element in additionalRequests is rejected with an indexed message")
        void shouldRejectNullAdditionalRequestElement() {
            List<RequestDefinitionDto> withNull = new ArrayList<>();
            withNull.add(chainRequest("second", List.of()));
            withNull.add(null);
            TestSuiteRequestDto dto = chainRequestWithColumns(List.of(), withNull);

            assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests[1]")
                    .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("Response column union exceeding MAX_RESPONSE_COLUMNS is rejected")
        void shouldRejectUnionOverCap() {
            List<ResponseColumnDefinitionDto> suiteColumns = namedColumns("s", 30);
            List<ResponseColumnDefinitionDto> additionalColumns = namedColumns("a", 21);
            TestSuiteRequestDto dto =
                    chainRequestWithColumns(suiteColumns, List.of(chainRequest("second", additionalColumns)));

            assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("51")
                    .hasMessageContaining("exceeds maximum of 50");
        }

        @Test
        @DisplayName("Response column union exactly at MAX_RESPONSE_COLUMNS is accepted")
        void shouldAcceptUnionAtCap() {
            List<ResponseColumnDefinitionDto> suiteColumns = namedColumns("s", 30);
            List<ResponseColumnDefinitionDto> additionalColumns = namedColumns("a", 20);
            TestSuiteRequestDto dto =
                    chainRequestWithColumns(suiteColumns, List.of(chainRequest("second", additionalColumns)));

            assertThatCode(() -> validator.validateTestSuiteSchemas(dto)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Non-empty additionalRequests on an MCP_TOOL suite is rejected")
        void shouldRejectAdditionalRequestsOnMcpToolSuite() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("MCP Suite")
                    .suiteType(SuiteType.MCP_TOOL)
                    .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                            .id("d")
                            .name("D")
                            .build())
                    .toolRef(ToolReferenceDto.builder().name("tool").build())
                    .additionalRequests(List.of(chainRequest("second", List.of())))
                    .build();

            assertThatThrownBy(() -> validator.validateSuiteTypeFields(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests")
                    .hasMessageContaining("MCP_TOOL");
        }

        @Test
        @DisplayName("Empty additionalRequests on an MCP_TOOL suite is accepted")
        void shouldAcceptEmptyAdditionalRequestsOnMcpToolSuite() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("MCP Suite")
                    .suiteType(SuiteType.MCP_TOOL)
                    .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                            .id("d")
                            .name("D")
                            .build())
                    .toolRef(ToolReferenceDto.builder().name("tool").build())
                    .additionalRequests(List.of())
                    .build();

            assertThatCode(() -> validator.validateSuiteTypeFields(dto)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Invalid endpointRef schema on an additional request is rejected with an indexed message")
        void shouldRejectInvalidEndpointSchemaOnAdditionalRequest() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .endpointRef(EndpointContractDto.builder()
                                    .relativeUrlPattern("/v1/second")
                                    .requestBodySchema(
                                            JsonRequestBodySchemaDto.builder().build())
                                    .build())
                            .requestTemplate(RequestTemplateDto.builder()
                                    .urlTemplate("/v1/second")
                                    .build())
                            .build()))
                    .build();
            // SchemaValidationService is mocked to fail every schema — the test only asserts the
            // message is prefixed with the additional request's index, not the exact schema error text.
            when(schemaValidationServiceMock.getSchemaValidationError(any(RequestBodySchemaDto.class)))
                    .thenReturn(Optional.of("boom"));

            assertThatThrownBy(() -> validatorWithMockedSchema.validateTestSuiteSchemas(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests[0].endpointRef");
        }

        @Test
        @DisplayName("content/jsonataContent mutual exclusivity is enforced on an additional request")
        void shouldRejectBothBodyFieldsOnAdditionalRequest() {
            TestSuiteRequestDto dto = chainRequestWithBody(RequestTemplateDto.builder()
                    .urlTemplate("/v1/second")
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("a", 1))
                            .jsonataContent("{\"a\": 1}")
                            .build())
                    .build());

            assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests[0].requestTemplate.body")
                    .hasMessageContaining("mutually exclusive");
        }

        @Test
        @DisplayName("Invalid jsonataContent syntax is rejected on an additional request")
        void shouldRejectInvalidJsonataContentOnAdditionalRequest() {
            TestSuiteRequestDto dto = chainRequestWithBody(RequestTemplateDto.builder()
                    .urlTemplate("/v1/second")
                    .body(JsonRequestBodyDto.builder()
                            .jsonataContent("choices[0.message.content")
                            .build())
                    .build());

            assertThatThrownBy(() -> validator.validateTestSuiteSchemas(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests[0].requestTemplate.body.jsonataContent");
        }

        @Test
        @DisplayName("Template size cap is enforced on an additional request")
        void shouldRejectOversizedTemplateOnAdditionalRequest() {
            ValidationProperties tinyLimits = new ValidationProperties();
            tinyLimits.setMaxTemplateSizeBytes(10);
            tinyLimits.setMaxBindingsCount(64);
            JsonbMapper jsonbMapper = new JsonbMapper(new ObjectMapper(), new RunnerJsonbMapper(new ObjectMapper()));
            TestSuiteRequestValidator tinyLimitValidator = new TestSuiteRequestValidator(
                    mock(JsonataEvaluationService.class),
                    mock(JsonataSourcePreprocessor.class),
                    mock(SchemaValidationService.class),
                    new ObjectMapper(),
                    tinyLimits,
                    new ResponseColumnUnionResolver(jsonbMapper));
            // request #0 has no requestTemplate at all, so validateTemplateLimits skips its own size
            // check entirely — isolating the assertion to the additional request's oversized template.
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .requestTemplate(RequestTemplateDto.builder()
                                    .urlTemplate("/v1/a-fairly-long-url-template-to-exceed-ten-bytes")
                                    .build())
                            .build()))
                    .build();

            assertThatThrownBy(() -> tinyLimitValidator.validateTemplateLimits(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests[0].requestTemplate")
                    .hasMessageContaining("exceeds maximum size");
        }

        @Test
        @DisplayName("inputBindings count cap is enforced on an additional request")
        void shouldRejectTooManyBindingsOnAdditionalRequest() {
            List<InputBindingDto> tooMany = new ArrayList<>();
            for (int i = 0; i < 65; i++) {
                tooMany.add(InputBindingDto.builder()
                        .templateVariable("v" + i)
                        .dataField("f" + i)
                        .build());
            }
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .requestTemplate(RequestTemplateDto.builder()
                                    .urlTemplate("/v1/second")
                                    .build())
                            .inputBindings(tooMany)
                            .build()))
                    .build();

            assertThatThrownBy(() -> validator.validateTemplateLimits(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests[0].inputBindings count")
                    .hasMessageContaining("exceeds maximum of 64");
        }

        @Test
        @DisplayName("Duplicate templateVariable within one additional request's inputBindings is rejected")
        void shouldRejectDuplicateTemplateVariableWithinAdditionalRequest() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .requestTemplate(RequestTemplateDto.builder()
                                    .urlTemplate("/v1/second")
                                    .build())
                            .inputBindings(List.of(
                                    InputBindingDto.builder()
                                            .templateVariable("q")
                                            .dataField("f1")
                                            .build(),
                                    InputBindingDto.builder()
                                            .templateVariable("q")
                                            .dataField("f2")
                                            .build()))
                            .build()))
                    .build();

            assertThatThrownBy(() -> validator.validateTemplateLimits(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests[0].Duplicate templateVariable 'q'");
        }

        private final SchemaValidationService schemaValidationServiceMock = mock(SchemaValidationService.class);
        private final TestSuiteRequestValidator validatorWithMockedSchema = buildValidatorWithMockedSchema();

        private TestSuiteRequestValidator buildValidatorWithMockedSchema() {
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
            JsonbMapper jsonbMapper = new JsonbMapper(new ObjectMapper(), new RunnerJsonbMapper(new ObjectMapper()));
            return new TestSuiteRequestValidator(
                    jsonataEvaluationService,
                    jsonataSourcePreprocessor,
                    schemaValidationServiceMock,
                    new ObjectMapper(),
                    validationProperties,
                    new ResponseColumnUnionResolver(jsonbMapper));
        }

        private TestSuiteRequestDto chainRequestWithColumns(
                List<ResponseColumnDefinitionDto> suiteColumns, List<RequestDefinitionDto> additionalRequests) {
            return TestSuiteRequestDto.builder()
                    .name("Suite")
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .responseColumns(suiteColumns)
                    .additionalRequests(additionalRequests)
                    .build();
        }

        private TestSuiteRequestDto chainRequestWithBody(RequestTemplateDto additionalRequestTemplate) {
            return TestSuiteRequestDto.builder()
                    .name("Suite")
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .requestTemplate(additionalRequestTemplate)
                            .build()))
                    .build();
        }

        private RequestDefinitionDto chainRequest(String name, List<ResponseColumnDefinitionDto> columns) {
            return RequestDefinitionDto.builder()
                    .name(name)
                    .requestTemplate(RequestTemplateDto.builder()
                            .urlTemplate("/v1/second")
                            .build())
                    .responseColumns(columns)
                    .build();
        }

        private ResponseColumnDefinitionDto column(String name, String expression) {
            return ResponseColumnDefinitionDto.builder()
                    .name(name)
                    .expression(expression)
                    .build();
        }

        private List<ResponseColumnDefinitionDto> namedColumns(String prefix, int count) {
            List<ResponseColumnDefinitionDto> columns = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                columns.add(column(prefix + i, "usage.total_tokens"));
            }
            return columns;
        }
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
