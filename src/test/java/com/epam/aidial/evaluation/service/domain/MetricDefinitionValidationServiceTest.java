package com.epam.aidial.evaluation.service.domain;

import static com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode.INVALID_OUTPUT_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MetricDefinitionValidationService")
class MetricDefinitionValidationServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String VALID_OUTPUT_SCHEMA = """
            {"properties": {"score": {"type": "number"}}}
            """;

    private MetricDefinitionValidationService service;

    private static final String SCHEMA_WITH_REQUIRED = """
            {
              "properties": {
                "reference": {"type": "string"},
                "actual": {"type": "string"},
                "threshold": {"type": "number"}
              },
              "required": ["reference", "actual"]
            }
            """;

    private static final String TEST_CASE_SCHEMA = """
            [{"name": "expected_output"}, {"name": "input"}]
            """;

    private static final String RESPONSE_COLUMNS = """
            [{"name": "model_answer"}, {"name": "score"}]
            """;

    @BeforeEach
    void setUp() {
        ValidationWarningsSerializer serializer = new ValidationWarningsSerializer(OBJECT_MAPPER);
        OutputSchemaFieldExtractor extractor = new OutputSchemaFieldExtractor(OBJECT_MAPPER);
        service = new MetricDefinitionValidationService(OBJECT_MAPPER, serializer, extractor);
    }

    @Test
    @DisplayName("All-valid bindings produce no warnings")
    void allValidBindings_noWarnings() {
        List<MetricParameterBindingDto> inputBindings = List.of(
                binding(
                        "reference",
                        TestCaseBindingSourceDto.builder()
                                .columnName("expected_output")
                                .build()),
                binding(
                        "actual",
                        ResponseBindingSourceDto.builder()
                                .columnName("model_answer")
                                .build()),
                binding(
                        "threshold",
                        ConstantBindingSourceDto.builder().value(0.8).build()));

        ValidationResult result = service.validate(
                List.of(),
                inputBindings,
                "{}",
                SCHEMA_WITH_REQUIRED,
                TEST_CASE_SCHEMA,
                RESPONSE_COLUMNS,
                VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("TestCase column reference that does not exist produces UNRESOLVED_REFERENCE warning")
    void testCaseColumnRef_unresolved_producesWarning() {
        List<MetricParameterBindingDto> inputBindings = List.of(
                binding(
                        "reference",
                        TestCaseBindingSourceDto.builder()
                                .columnName("nonexistent_column")
                                .build()),
                binding(
                        "actual",
                        ResponseBindingSourceDto.builder()
                                .columnName("model_answer")
                                .build()));

        ValidationResult result = service.validate(
                List.of(),
                inputBindings,
                "{}",
                SCHEMA_WITH_REQUIRED,
                TEST_CASE_SCHEMA,
                RESPONSE_COLUMNS,
                VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.UNRESOLVED_REFERENCE
                        && "reference".equals(w.getFieldName())
                        && "$.inputBindings".equals(w.getPath()));
    }

    @Test
    @DisplayName("Response column reference that does not exist produces UNRESOLVED_REFERENCE warning")
    void responseColumnRef_unresolved_producesWarning() {
        List<MetricParameterBindingDto> inputBindings = List.of(
                binding(
                        "reference",
                        TestCaseBindingSourceDto.builder()
                                .columnName("expected_output")
                                .build()),
                binding(
                        "actual",
                        ResponseBindingSourceDto.builder()
                                .columnName("nonexistent_column")
                                .build()));

        ValidationResult result = service.validate(
                List.of(),
                inputBindings,
                "{}",
                SCHEMA_WITH_REQUIRED,
                TEST_CASE_SCHEMA,
                RESPONSE_COLUMNS,
                VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.UNRESOLVED_REFERENCE
                        && "actual".equals(w.getFieldName())
                        && "$.inputBindings".equals(w.getPath()));
    }

    @Test
    @DisplayName("UNRESOLVED_REFERENCE warning in configBindings has path '$.configBindings'")
    void testCaseColumnRef_unresolved_inConfigBindings_hasCorrectPath() {
        List<MetricParameterBindingDto> configBindings = List.of(binding(
                "reference",
                TestCaseBindingSourceDto.builder()
                        .columnName("nonexistent_column")
                        .build()));

        ValidationResult result = service.validate(
                configBindings,
                List.of(),
                SCHEMA_WITH_REQUIRED,
                "{}",
                TEST_CASE_SCHEMA,
                RESPONSE_COLUMNS,
                VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.UNRESOLVED_REFERENCE
                        && "reference".equals(w.getFieldName())
                        && "$.configBindings".equals(w.getPath()));
    }

    @Test
    @DisplayName("Required property has no binding produces REQUIRED warning")
    void requiredProperty_noBinding_producesWarning() {
        // Only bind 'actual', leave 'reference' (required) unbound
        List<MetricParameterBindingDto> inputBindings = List.of(binding(
                "actual",
                ResponseBindingSourceDto.builder().columnName("model_answer").build()));

        ValidationResult result = service.validate(
                List.of(),
                inputBindings,
                "{}",
                SCHEMA_WITH_REQUIRED,
                TEST_CASE_SCHEMA,
                RESPONSE_COLUMNS,
                VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED && "reference".equals(w.getFieldName()));
    }

    @Test
    @DisplayName("Required property bound to null constant produces REQUIRED warning")
    void requiredProperty_boundToNullConstant_producesWarning() {
        List<MetricParameterBindingDto> inputBindings = List.of(
                binding(
                        "reference",
                        ConstantBindingSourceDto.builder().value(null).build()),
                binding(
                        "actual",
                        ResponseBindingSourceDto.builder()
                                .columnName("model_answer")
                                .build()));

        ValidationResult result = service.validate(
                List.of(),
                inputBindings,
                "{}",
                SCHEMA_WITH_REQUIRED,
                TEST_CASE_SCHEMA,
                RESPONSE_COLUMNS,
                VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED && "reference".equals(w.getFieldName()));
    }

    @Test
    @DisplayName("Optional property bound to null constant does NOT produce a warning")
    void optionalProperty_boundToNullConstant_noWarning() {
        // 'threshold' is not in "required" array
        List<MetricParameterBindingDto> inputBindings = List.of(
                binding(
                        "reference",
                        TestCaseBindingSourceDto.builder()
                                .columnName("expected_output")
                                .build()),
                binding(
                        "actual",
                        ResponseBindingSourceDto.builder()
                                .columnName("model_answer")
                                .build()),
                binding(
                        "threshold",
                        ConstantBindingSourceDto.builder().value(null).build()));

        ValidationResult result = service.validate(
                List.of(),
                inputBindings,
                "{}",
                SCHEMA_WITH_REQUIRED,
                TEST_CASE_SCHEMA,
                RESPONSE_COLUMNS,
                VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Binding targeting unknown schema property produces ADDITIONAL warning")
    void binding_unknownSchemaProperty_producesAdditional() {
        List<MetricParameterBindingDto> inputBindings = List.of(
                binding(
                        "reference",
                        TestCaseBindingSourceDto.builder()
                                .columnName("expected_output")
                                .build()),
                binding(
                        "actual",
                        ResponseBindingSourceDto.builder()
                                .columnName("model_answer")
                                .build()),
                binding(
                        "unknown_prop",
                        ConstantBindingSourceDto.builder().value("val").build()));

        ValidationResult result = service.validate(
                List.of(),
                inputBindings,
                "{}",
                SCHEMA_WITH_REQUIRED,
                TEST_CASE_SCHEMA,
                RESPONSE_COLUMNS,
                VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w ->
                        w.getCode() == ValidationWarningCode.ADDITIONAL && "unknown_prop".equals(w.getFieldName()));
    }

    @Test
    @DisplayName("Schema without 'properties' key — no ADDITIONAL warnings, no REQUIRED warnings")
    void schemaWithoutProperties_gracefulDegradation() {
        List<MetricParameterBindingDto> inputBindings = List.of(binding(
                "anything", ConstantBindingSourceDto.builder().value("val").build()));

        ValidationResult result = service.validate(
                List.of(), inputBindings, "{}", "{}", TEST_CASE_SCHEMA, RESPONSE_COLUMNS, VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Valid output schema passes — no INVALID_OUTPUT_SCHEMA warning")
    void validOutputSchema_noWarning() {
        ValidationResult result = service.validate(
                List.of(), List.of(), "{}", "{}", TEST_CASE_SCHEMA, RESPONSE_COLUMNS, VALID_OUTPUT_SCHEMA);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).noneMatch(w -> w.getCode() == INVALID_OUTPUT_SCHEMA);
    }

    @Test
    @DisplayName("Null output schema produces INVALID_OUTPUT_SCHEMA warning and valid=false")
    void nullOutputSchema_producesInvalidOutputSchemaWarning() {
        ValidationResult result =
                service.validate(List.of(), List.of(), "{}", "{}", TEST_CASE_SCHEMA, RESPONSE_COLUMNS, null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(w -> w.getCode() == INVALID_OUTPUT_SCHEMA);
    }

    @Test
    @DisplayName("Output schema with empty properties produces INVALID_OUTPUT_SCHEMA warning")
    void emptyPropertiesInOutputSchema_producesWarning() {
        ValidationResult result = service.validate(
                List.of(), List.of(), "{}", "{}", TEST_CASE_SCHEMA, RESPONSE_COLUMNS, "{\"properties\": {}}");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(w -> w.getCode() == INVALID_OUTPUT_SCHEMA);
    }

    @Test
    @DisplayName("Invalid output schema does not suppress binding warnings — both appear")
    void invalidOutputSchema_doesNotSuppressBindingWarnings() {
        List<MetricParameterBindingDto> inputBindings = List.of(binding(
                "reference",
                TestCaseBindingSourceDto.builder()
                        .columnName("nonexistent_column")
                        .build()));

        ValidationResult result = service.validate(
                List.of(), inputBindings, "{}", SCHEMA_WITH_REQUIRED, TEST_CASE_SCHEMA, RESPONSE_COLUMNS, null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(w -> w.getCode() == INVALID_OUTPUT_SCHEMA);
        assertThat(result.getWarnings()).anyMatch(w -> w.getCode() == ValidationWarningCode.UNRESOLVED_REFERENCE);
    }

    private static MetricParameterBindingDto binding(
            String property, com.epam.aidial.evaluation.service.domain.dto.MetricBindingSourceDto source) {
        return MetricParameterBindingDto.builder()
                .property(property)
                .source(source)
                .build();
    }
}
