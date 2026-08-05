package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;

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
                templateVariableExtractor, evaluationRunProperties, fileRefValidator, bindingValidator, jsonbMapper);
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
}
