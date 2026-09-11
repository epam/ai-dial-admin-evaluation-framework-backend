package com.epam.aidial.evaluation.service.domain;

import static com.epam.aidial.evaluation.runner.constants.ModelSelectingEndpointPaths.ANTHROPIC_MESSAGES;
import static com.epam.aidial.evaluation.runner.constants.ModelSelectingEndpointPaths.OPENAI_RESPONSES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.service.RequestModelValidator;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SuiteValidationServiceTest {

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @Mock
    private EvaluationRunProperties.Execution execution;

    @Mock
    private FileRefValidator fileRefValidator;

    @Mock
    private JsonbMapper jsonbMapper;

    private TemplateVariableExtractor templateVariableExtractor;
    private BindingValidator bindingValidator;
    private SuiteValidationService service;

    @BeforeEach
    void setUp() {
        templateVariableExtractor = new TemplateVariableExtractor();
        bindingValidator = new BindingValidator(fileRefValidator);
        service = new SuiteValidationService(
                templateVariableExtractor,
                evaluationRunProperties,
                fileRefValidator,
                bindingValidator,
                new McpArgumentValidator(
                        templateVariableExtractor, new JsonSchemaPropertyExtractor(new ObjectMapper())),
                jsonbMapper,
                new RequestModelValidator());
        lenient().when(evaluationRunProperties.getExecution()).thenReturn(execution);
        lenient().when(execution.getHeaderBlacklist()).thenReturn(List.of());
    }

    @Nested
    @DisplayName("FILE form part placeholder validation")
    class FileFormPartPlaceholderValidation {

        private static final UUID SUITE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        @Test
        @DisplayName("FILE part with ${{contract_file}} placeholder produces no warning")
        void shouldSkipValidationForSimplePlaceholder() {
            TestSuiteRequestDto dto = buildDeploymentSuiteWithFilePart("${{contract_file}}");

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            List<ValidationWarningDto> filePartWarnings = result.getWarnings().stream()
                    .filter(w -> w.getMessage() != null && w.getMessage().contains("FILE form part"))
                    .toList();
            assertThat(filePartWarnings).isEmpty();
            verify(fileRefValidator, never()).validateSuiteOwnership(eq("${{contract_file}}"), any());
        }

        @Test
        @DisplayName("FILE part with ${{attachment|file}} placeholder produces no warning")
        void shouldSkipValidationForTypedPlaceholder() {
            TestSuiteRequestDto dto = buildDeploymentSuiteWithFilePart("${{attachment|file}}");

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            List<ValidationWarningDto> filePartWarnings = result.getWarnings().stream()
                    .filter(w -> w.getMessage() != null && w.getMessage().contains("FILE form part"))
                    .toList();
            assertThat(filePartWarnings).isEmpty();
            verify(fileRefValidator, never()).validateSuiteOwnership(eq("${{attachment|file}}"), any());
        }

        @Test
        @DisplayName("FILE part with literal @ef ref still validates normally")
        void shouldValidateLiteralFileRef() {
            String literalRef = "@ef/suites/" + SUITE_ID + "/report.pdf";
            when(fileRefValidator.validateSuiteOwnership(literalRef, SUITE_ID)).thenReturn(List.of());

            TestSuiteRequestDto dto = buildDeploymentSuiteWithFilePart(literalRef);

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            verify(fileRefValidator).validateSuiteOwnership(literalRef, SUITE_ID);
            List<ValidationWarningDto> filePartWarnings = result.getWarnings().stream()
                    .filter(w -> w.getMessage() != null && w.getMessage().contains("FILE form part"))
                    .toList();
            assertThat(filePartWarnings).isEmpty();
        }

        @Test
        @DisplayName("FILE part with invalid literal produces warning")
        void shouldProduceWarningForInvalidLiteralRef() {
            String invalidRef = "public/..";
            when(fileRefValidator.validateSuiteOwnership(invalidRef, SUITE_ID))
                    .thenReturn(List.of("File reference must not contain '..' path traversal: " + invalidRef));

            TestSuiteRequestDto dto = buildDeploymentSuiteWithFilePart(invalidRef);

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            List<ValidationWarningDto> filePartWarnings = result.getWarnings().stream()
                    .filter(w -> w.getMessage() != null && w.getMessage().contains("FILE form part"))
                    .toList();
            assertThat(filePartWarnings).hasSize(1);
        }

        private TestSuiteRequestDto buildDeploymentSuiteWithFilePart(String fileValue) {
            return TestSuiteRequestDto.builder()
                    .name("Test Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern("/upload")
                            .build())
                    .requestTemplate(RequestTemplateDto.builder()
                            .urlTemplate("/upload")
                            .body(MultipartFormDataRequestBodyDto.builder()
                                    .content(List.of(FormPartDto.builder()
                                            .name("file_attachment")
                                            .type(FormPartType.FILE)
                                            .value(fileValue)
                                            .build()))
                                    .build())
                            .build())
                    .build();
        }
    }

    @Nested
    @DisplayName("MCP binding validation")
    class McpBindingValidation {

        private static final UUID SUITE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        @Test
        @DisplayName("Required variable without binding produces REQUIRED warning")
        void shouldProduceRequiredWarning() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("query", "${{userQuery}}"), List.of(), List.of(field("someField", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED
                            && w.getFieldName().equals("userQuery"));
        }

        @Test
        @DisplayName("Binding to unknown schema field produces UNKNOWN warning")
        void shouldProduceUnknownWarning() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("query", "${{userQuery}}"),
                    List.of(binding("userQuery", "nonexistent", null)),
                    List.of(field("question", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.UNKNOWN
                            && w.getFieldName().equals("nonexistent"));
        }

        @Test
        @DisplayName("Orphan binding produces ADDITIONAL warning")
        void shouldProduceAdditionalWarning() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("query", "${{userQuery}}"),
                    List.of(binding("userQuery", "question", null), binding("unused", "field", null)),
                    List.of(field("question", SchemaFieldType.STRING), field("field", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.ADDITIONAL
                            && w.getFieldName().equals("unused"));
        }

        @Test
        @DisplayName("|file constant binding with valid ref produces no warning")
        void shouldProduceNoWarningForValidFileBinding() {
            when(fileRefValidator.validateSuiteOwnership("public/shared/input.csv", SUITE_ID))
                    .thenReturn(List.of());

            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("document", "${{doc|file}}"),
                    List.of(binding("doc", null, "public/shared/input.csv")),
                    List.of());

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.getWarnings())
                    .noneMatch(w -> w.getCode() == ValidationWarningCode.TYPE
                            && w.getFieldName() != null
                            && w.getFieldName().equals("doc"));
        }

        @Test
        @DisplayName("|file constant binding with invalid ref produces TYPE warning")
        void shouldProduceTypeWarningForInvalidFileBinding() {
            when(fileRefValidator.validateSuiteOwnership("invalid-prefix/path", SUITE_ID))
                    .thenReturn(List.of("File reference uses disallowed prefix 'invalid-prefix'"));

            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("document", "${{doc|file}}"),
                    List.of(binding("doc", null, "invalid-prefix/path")),
                    List.of());

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.TYPE
                            && w.getFieldName().equals("doc"));
        }

        @Test
        @DisplayName("Unrecognized type hint produces TYPE warning")
        void shouldProduceTypeWarningForUnrecognizedTypeHint() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("data", "${{input|unknown_type}}"),
                    List.of(binding("input", "field", null)),
                    List.of(field("field", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.TYPE
                            && w.getMessage().contains("unknown_type"));
        }

        @Test
        @DisplayName("All bindings valid produces no binding warnings")
        void shouldProduceNoWarningsWhenAllBindingsValid() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("query", "${{userQuery}}"),
                    List.of(binding("userQuery", "question", null)),
                    List.of(field("question", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("Null argumentTemplate produces ADDITIONAL warning and valid = false")
        void shouldProduceWarningForNullArgumentTemplate() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("MCP Suite")
                    .suiteType(SuiteType.MCP_TOOL)
                    .argumentTemplate(null)
                    .build();

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings()).hasSize(1);
            assertThat(result.getWarnings().get(0).getCode()).isEqualTo(ValidationWarningCode.ADDITIONAL);
            assertThat(result.getWarnings().get(0).getMessage()).contains("argumentTemplate");
        }

        @Test
        @DisplayName("Empty bindings list with required variables produces REQUIRED warnings")
        void shouldProduceRequiredWarningsForEmptyBindings() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("query", "${{userQuery}}"),
                    List.of(), // explicit empty
                    List.of(field("question", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED
                            && w.getFieldName().equals("userQuery"));
        }

        @Test
        @DisplayName("Null bindings with required variables produces REQUIRED warnings (same as empty)")
        void shouldProduceRequiredWarningsForNullBindings() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("query", "${{userQuery}}"), null, List.of(field("question", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED
                            && w.getFieldName().equals("userQuery"));
        }

        @Test
        @DisplayName("Duplicate variable in argumentTemplate with single binding produces no REQUIRED warning")
        void shouldDeduplicateVariables() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("q1", "${{query}}", "q2", "${{query}}"),
                    List.of(binding("query", "question", null)),
                    List.of(field("question", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.getWarnings()).noneMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED);
        }

        @Test
        @DisplayName("|file binding with dataField produces no file ref warning")
        void shouldNotValidateFileRefForDataFieldBinding() {
            TestSuiteRequestDto dto = buildMcpSuite(
                    Map.of("document", "${{doc|file}}"),
                    List.of(binding("doc", "file_path", null)),
                    List.of(field("file_path", SchemaFieldType.STRING)));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, lastSchema);

            assertThat(result.getWarnings())
                    .noneMatch(w -> w.getCode() == ValidationWarningCode.TYPE
                            && w.getFieldName() != null
                            && w.getFieldName().equals("doc"));
        }

        private List<FieldDefinitionDto> lastSchema;

        private TestSuiteRequestDto buildMcpSuite(
                Map<String, Object> arguments, List<InputBindingDto> bindings, List<FieldDefinitionDto> schema) {
            this.lastSchema = schema;
            return TestSuiteRequestDto.builder()
                    .name("MCP Suite")
                    .suiteType(SuiteType.MCP_TOOL)
                    .argumentTemplate(
                            ArgumentTemplateDto.builder().arguments(arguments).build())
                    .inputBindings(bindings)
                    .build();
        }

        private InputBindingDto binding(String templateVariable, String dataField, Object constantValue) {
            return InputBindingDto.builder()
                    .templateVariable(templateVariable)
                    .dataField(dataField)
                    .constantValue(constantValue)
                    .build();
        }

        private FieldDefinitionDto field(String name, SchemaFieldType type) {
            return FieldDefinitionDto.builder().name(name).type(type).build();
        }
    }

    @Nested
    @DisplayName("MCP tool schema validation")
    class McpToolSchemaValidation {

        private static final UUID SUITE_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

        @Test
        @DisplayName("Required tool argument saved with an empty constant makes the suite invalid")
        void shouldInvalidateSuite_whenRequiredArgumentIsEmpty() {
            TestSuiteRequestDto dto = buildMcpSuiteWithTool(Map.of("repoName", ""), List.of());

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED
                            && "repoName".equals(w.getFieldName())
                            && "$.argumentTemplate.arguments".equals(w.getPath()));
        }

        @Test
        @DisplayName("Unbound placeholder for a required argument is reported once, not twice")
        void shouldNotDoubleWarn_whenPlaceholderHasNoBinding() {
            TestSuiteRequestDto dto = buildMcpSuiteWithTool(Map.of("repoName", "${{repo}}"), List.of());

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.getWarnings())
                    .filteredOn(w -> w.getCode() == ValidationWarningCode.REQUIRED)
                    .singleElement()
                    .satisfies(w -> assertThat(w.getFieldName()).isEqualTo("repo"));
        }

        private TestSuiteRequestDto buildMcpSuiteWithTool(
                Map<String, Object> arguments, List<InputBindingDto> bindings) {
            return TestSuiteRequestDto.builder()
                    .name("MCP Suite")
                    .suiteType(SuiteType.MCP_TOOL)
                    .toolRef(ToolReferenceDto.builder()
                            .name("github_search")
                            .inputSchema(Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of("repoName", Map.of("type", "string"), "branch", Map.of("type", "string")),
                                    "required",
                                    List.of("repoName")))
                            .build())
                    .argumentTemplate(
                            ArgumentTemplateDto.builder().arguments(arguments).build())
                    .inputBindings(bindings)
                    .build();
        }
    }

    @Nested
    @DisplayName("Request chain warning-path prefixing (design.md D13)")
    class RequestChainPathPrefixing {

        private static final UUID SUITE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        @Test
        @DisplayName("Request #0's missing urlTemplate warning path is byte-identical to before request chains")
        void requestZeroMissingUrlTemplate_pathIsUnprefixed() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern("/v1/chat")
                            .build())
                    .requestTemplate(RequestTemplateDto.builder().build())
                    .build();

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.getWarnings())
                    .anyMatch(w -> "$.urlTemplate".equals(w.getPath())
                            && w.getMessage().contains("urlTemplate is required"));
        }

        @Test
        @DisplayName("Missing urlTemplate on an additional request produces the indexed path")
        void additionalRequestMissingUrlTemplate_pathIsIndexed() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern("/v1/chat")
                            .build())
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .requestTemplate(RequestTemplateDto.builder().build())
                            .build()))
                    .build();

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> "$.additionalRequests[0].requestTemplate.urlTemplate".equals(w.getPath())
                            && w.getMessage().contains("urlTemplate is required"));
        }

        @Test
        @DisplayName("Missing endpointRef on an additional request produces the indexed path")
        void additionalRequestMissingEndpointRef_pathIsIndexed() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern("/v1/chat")
                            .build())
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .requestTemplate(RequestTemplateDto.builder()
                                    .urlTemplate("/v1/second")
                                    .build())
                            .build()))
                    .build();

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.getWarnings())
                    .anyMatch(w -> "$.additionalRequests[0].endpointRef".equals(w.getPath())
                            && w.getMessage().contains("endpointRef is required"));
        }

        @Test
        @DisplayName("Unresolvable binding on an additional request produces the indexed inputBindings path")
        void additionalRequestUnresolvableBinding_pathIsIndexed() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern("/v1/chat")
                            .build())
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .endpointRef(EndpointContractDto.builder()
                                    .method(HttpMethod.POST)
                                    .relativeUrlPattern("/v1/second")
                                    .build())
                            .requestTemplate(RequestTemplateDto.builder()
                                    .urlTemplate("/v1/second/${{missingField}}")
                                    .build())
                            .build()))
                    .build();

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> "$.additionalRequests[0].inputBindings".equals(w.getPath())
                            && w.getCode() == ValidationWarningCode.REQUIRED);
        }

        @Test
        @DisplayName("Warnings from request #0 and an additional request aggregate, distinguishable by path")
        void warningsFromBothRequestsAggregate() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern("/v1/chat")
                            .build())
                    .requestTemplate(RequestTemplateDto.builder().build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .requestTemplate(RequestTemplateDto.builder().build())
                            .build()))
                    .build();

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings()).anyMatch(w -> "$.urlTemplate".equals(w.getPath()));
            assertThat(result.getWarnings())
                    .anyMatch(w -> "$.additionalRequests[0].requestTemplate.urlTemplate".equals(w.getPath()));
        }

        @Test
        @DisplayName("A valid two-request chain produces no chain-related warnings")
        void validTwoRequestChain_producesNoWarnings() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern("/v1/chat")
                            .build())
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(RequestDefinitionDto.builder()
                            .name("second")
                            .endpointRef(EndpointContractDto.builder()
                                    .method(HttpMethod.POST)
                                    .relativeUrlPattern("/v1/second")
                                    .build())
                            .requestTemplate(RequestTemplateDto.builder()
                                    .urlTemplate("/v1/second")
                                    .build())
                            .build()))
                    .build();

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Fixed-path model-selecting API request-model validation")
    class FixedPathRequestModelValidation {

        private static final UUID SUITE_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ANTHROPIC_MESSAGES, OPENAI_RESPONSES})
        @DisplayName("Literal model matching deploymentRef.id produces no warning")
        void modelMatchesDeploymentRef_noWarning(String relativeUrl) {
            TestSuiteRequestDto dto = buildFixedPathSuite(relativeUrl, "gpt-4o", Map.of("model", "gpt-4o"), null);

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ANTHROPIC_MESSAGES, OPENAI_RESPONSES})
        @DisplayName("Mismatched literal model on request #0 produces a warning at $.requestTemplate.body")
        void modelMismatchOnRequestZero_warns(String relativeUrl) {
            TestSuiteRequestDto dto =
                    buildFixedPathSuite(relativeUrl, "claude-3-5-sonnet-v2", Map.of("model", "claude-3-opus"), null);

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR
                            && "$.requestTemplate.body".equals(w.getPath())
                            && "model".equals(w.getFieldName())
                            && w.getMessage().contains("claude-3-opus")
                            && w.getMessage().contains("claude-3-5-sonnet-v2"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ANTHROPIC_MESSAGES, OPENAI_RESPONSES})
        @DisplayName("Missing model key produces a REQUEST_BODY_VALIDATION_ERROR warning")
        void modelMissing_warns(String relativeUrl) {
            TestSuiteRequestDto dto = buildFixedPathSuite(relativeUrl, "gpt-4o", Map.of("input", "hi"), null);

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR
                            && "$.requestTemplate.body".equals(w.getPath()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ANTHROPIC_MESSAGES, OPENAI_RESPONSES})
        @DisplayName("JSON null model produces a REQUEST_BODY_VALIDATION_ERROR warning")
        void modelNull_warns(String relativeUrl) {
            Map<String, Object> content = new HashMap<>();
            content.put("model", null);
            TestSuiteRequestDto dto = buildFixedPathSuite(relativeUrl, "gpt-4o", content, null);

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR
                            && "$.requestTemplate.body".equals(w.getPath()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ANTHROPIC_MESSAGES, OPENAI_RESPONSES})
        @DisplayName("Non-string model produces a REQUEST_BODY_VALIDATION_ERROR warning")
        void modelNonString_warns(String relativeUrl) {
            TestSuiteRequestDto dto = buildFixedPathSuite(relativeUrl, "gpt-4o", Map.of("model", 42), null);

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR
                            && "$.requestTemplate.body".equals(w.getPath()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ANTHROPIC_MESSAGES, OPENAI_RESPONSES})
        @DisplayName("Model containing an embedded ${{...}} placeholder produces a warning")
        void modelWithEmbeddedPlaceholder_warns(String relativeUrl) {
            TestSuiteRequestDto dto =
                    buildFixedPathSuite(relativeUrl, "gpt-4o", Map.of("model", "prefix-${{model}}"), null);
            dto.setInputBindings(List.of(InputBindingDto.builder()
                    .templateVariable("model")
                    .constantValue("gpt-4o")
                    .build()));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR
                            && "$.requestTemplate.body".equals(w.getPath()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ANTHROPIC_MESSAGES, OPENAI_RESPONSES})
        @DisplayName("jsonataContent body is deferred to run time and produces no model warning")
        void jsonataContentBody_deferred(String relativeUrl) {
            TestSuiteRequestDto dto = buildFixedPathSuite(
                    relativeUrl, "claude-3-5-sonnet-v2", null, "{ \"model\": \"claude-3-opus\", \"messages\": [] }");

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "/chat/completions",
                    "/openai/v1/responses/sub",
                    "/OpenAI/v1/Responses",
                    "/anthropic/v1/Messages"
                })
        @DisplayName("Non-canonical endpoint with a mismatched literal model is not checked")
        void nonCanonicalEndpoint_notChecked(String relativeUrl) {
            TestSuiteRequestDto dto =
                    buildFixedPathSuite(relativeUrl, "gpt-4o", Map.of("model", "some-unrelated-value"), null);

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isTrue();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("Mismatched literal model on additionalRequests[1] produces a warning at the indexed path")
        void modelMismatchOnAdditionalRequest_warnsAtIndexedPath() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .name("Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .deploymentRef(DeploymentReferenceDto.builder()
                            .id("claude-3-5-sonnet-v2")
                            .build())
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern("/v1/chat")
                            .build())
                    .requestTemplate(
                            RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                    .additionalRequests(List.of(
                            RequestDefinitionDto.builder()
                                    .name("first-extra")
                                    .endpointRef(EndpointContractDto.builder()
                                            .method(HttpMethod.POST)
                                            .relativeUrlPattern("/v1/chat")
                                            .build())
                                    .requestTemplate(RequestTemplateDto.builder()
                                            .urlTemplate("/v1/chat")
                                            .build())
                                    .build(),
                            RequestDefinitionDto.builder()
                                    .name("second-extra")
                                    .endpointRef(EndpointContractDto.builder()
                                            .method(HttpMethod.POST)
                                            .relativeUrlPattern(OPENAI_RESPONSES)
                                            .build())
                                    .requestTemplate(RequestTemplateDto.builder()
                                            .urlTemplate(OPENAI_RESPONSES)
                                            .body(JsonRequestBodyDto.builder()
                                                    .content(Map.of("model", "claude-3-opus"))
                                                    .build())
                                            .build())
                                    .build()))
                    .build();

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR
                            && "$.additionalRequests[1].requestTemplate.body".equals(w.getPath()));
        }

        @Test
        @DisplayName("A valid request #0 and an invalid additional request are validated independently")
        void chainRequestsValidatedIndependently() {
            TestSuiteRequestDto dto = buildFixedPathSuite(
                    ANTHROPIC_MESSAGES, "claude-3-5-sonnet-v2", Map.of("model", "claude-3-5-sonnet-v2"), null);
            dto.setAdditionalRequests(List.of(RequestDefinitionDto.builder()
                    .name("extra")
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern(OPENAI_RESPONSES)
                            .build())
                    .requestTemplate(RequestTemplateDto.builder()
                            .urlTemplate(OPENAI_RESPONSES)
                            .body(JsonRequestBodyDto.builder()
                                    .content(Map.of("model", "gpt-4o"))
                                    .build())
                            .build())
                    .build()));

            ValidationResult result = service.validateSuite(dto, SUITE_ID, List.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getWarnings())
                    .filteredOn(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR)
                    .extracting(ValidationWarningDto::getPath)
                    .containsExactly("$.additionalRequests[0].requestTemplate.body");
        }

        private TestSuiteRequestDto buildFixedPathSuite(
                String relativeUrl, String deploymentRefId, Map<String, Object> content, String jsonataContent) {
            JsonRequestBodyDto.JsonRequestBodyDtoBuilder bodyBuilder = JsonRequestBodyDto.builder();
            if (jsonataContent != null) {
                bodyBuilder.jsonataContent(jsonataContent);
            } else {
                bodyBuilder.content(content);
            }
            return TestSuiteRequestDto.builder()
                    .name("Suite")
                    .suiteType(SuiteType.DEPLOYMENT)
                    .deploymentRef(
                            DeploymentReferenceDto.builder().id(deploymentRefId).build())
                    .endpointRef(EndpointContractDto.builder()
                            .method(HttpMethod.POST)
                            .relativeUrlPattern(relativeUrl)
                            .build())
                    .requestTemplate(RequestTemplateDto.builder()
                            .urlTemplate(relativeUrl)
                            .body(bodyBuilder.build())
                            .build())
                    .build();
        }
    }
}
