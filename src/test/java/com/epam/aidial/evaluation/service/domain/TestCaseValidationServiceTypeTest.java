package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("TestCaseValidationService — type mismatch validation")
@ExtendWith(MockitoExtension.class)
class TestCaseValidationServiceTypeTest {

    @Mock
    private TemplateVariableExtractor templateVariableExtractor;

    @Mock
    private ValidationProperties validationProperties;

    @Mock
    private FileRefValidator fileRefValidator;

    @Mock
    private TestCaseProperties testCaseProperties;

    private TestCaseValidationService service;

    @BeforeEach
    void setUp() {
        service = new TestCaseValidationService(
                templateVariableExtractor,
                validationProperties,
                fileRefValidator,
                testCaseProperties,
                new TestCaseFieldScopeResolver());
        when(validationProperties.getMaxWarningsPerCase()).thenReturn(100);
        when(templateVariableExtractor.extract(any())).thenReturn(List.of());
    }

    private ValidationResult validate(Map<String, Object> data, List<FieldDefinitionDto> schema) {
        return service.validateTestCase(data, schema, null, List.of(), false, null);
    }

    private List<FieldDefinitionDto> schemaWith(String name, SchemaFieldType type) {
        return List.of(FieldDefinitionDto.builder()
                .name(name)
                .type(type)
                .required(false)
                .build());
    }

    private boolean hasTypeWarning(ValidationResult result, String fieldName) {
        return result.getWarnings().stream()
                .anyMatch(w -> w.getCode() == ValidationWarningCode.TYPE && fieldName.equals(w.getFieldName()));
    }

    // -------------------------------------------------------------------------
    // TYPE warning expected (mismatches)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Mismatches — TYPE warning emitted")
    class Mismatches {

        @Test
        @DisplayName("STRING + Long → TYPE warning")
        void stringPlusLong() {
            ValidationResult result = validate(Map.of("f", 42L), schemaWith("f", SchemaFieldType.STRING));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("STRING + Integer → TYPE warning")
        void stringPlusInteger() {
            ValidationResult result = validate(Map.of("f", 42), schemaWith("f", SchemaFieldType.STRING));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("STRING + Boolean → TYPE warning")
        void stringPlusBoolean() {
            ValidationResult result = validate(Map.of("f", true), schemaWith("f", SchemaFieldType.STRING));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("STRING + Double → TYPE warning")
        void stringPlusDouble() {
            ValidationResult result = validate(Map.of("f", 3.14), schemaWith("f", SchemaFieldType.STRING));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("INTEGER + String → TYPE warning")
        void integerPlusString() {
            ValidationResult result = validate(Map.of("f", "hello"), schemaWith("f", SchemaFieldType.INTEGER));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("INTEGER + Double → TYPE warning")
        void integerPlusDouble() {
            ValidationResult result = validate(Map.of("f", 3.14), schemaWith("f", SchemaFieldType.INTEGER));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("INTEGER + Boolean → TYPE warning")
        void integerPlusBoolean() {
            ValidationResult result = validate(Map.of("f", true), schemaWith("f", SchemaFieldType.INTEGER));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("BOOLEAN + String → TYPE warning")
        void booleanPlusString() {
            ValidationResult result = validate(Map.of("f", "true"), schemaWith("f", SchemaFieldType.BOOLEAN));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("BOOLEAN + Long → TYPE warning (API path, no coercion)")
        void booleanPlusLong() {
            ValidationResult result = validate(Map.of("f", 1L), schemaWith("f", SchemaFieldType.BOOLEAN));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("BOOLEAN + Integer → TYPE warning")
        void booleanPlusInteger() {
            ValidationResult result = validate(Map.of("f", 1), schemaWith("f", SchemaFieldType.BOOLEAN));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("BOOLEAN + Double → TYPE warning")
        void booleanPlusDouble() {
            ValidationResult result = validate(Map.of("f", 1.0), schemaWith("f", SchemaFieldType.BOOLEAN));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("NUMBER + String → TYPE warning")
        void numberPlusString() {
            ValidationResult result = validate(Map.of("f", "hello"), schemaWith("f", SchemaFieldType.NUMBER));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("NUMBER + Boolean → TYPE warning")
        void numberPlusBoolean() {
            ValidationResult result = validate(Map.of("f", true), schemaWith("f", SchemaFieldType.NUMBER));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("OBJECT + non-Map → TYPE warning")
        void objectPlusNonMap() {
            ValidationResult result = validate(Map.of("f", "hello"), schemaWith("f", SchemaFieldType.OBJECT));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("ARRAY + non-List → TYPE warning")
        void arrayPlusNonList() {
            ValidationResult result = validate(Map.of("f", "hello"), schemaWith("f", SchemaFieldType.ARRAY));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }

        @Test
        @DisplayName("FILE + Long → TYPE warning")
        void filePlusLong() {
            ValidationResult result = validate(Map.of("f", 42L), schemaWith("f", SchemaFieldType.FILE));
            assertThat(hasTypeWarning(result, "f")).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // No TYPE warning (compatible types)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Compatible types — no TYPE warning")
    class Compatible {

        @Test
        @DisplayName("STRING + String → no warning")
        void stringPlusString() {
            ValidationResult result = validate(Map.of("f", "hello"), schemaWith("f", SchemaFieldType.STRING));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("INTEGER + Integer → no warning")
        void integerPlusInteger() {
            ValidationResult result = validate(Map.of("f", 42), schemaWith("f", SchemaFieldType.INTEGER));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("INTEGER + Long → no warning")
        void integerPlusLong() {
            ValidationResult result = validate(Map.of("f", 42L), schemaWith("f", SchemaFieldType.INTEGER));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("NUMBER + Double → no warning")
        void numberPlusDouble() {
            ValidationResult result = validate(Map.of("f", 3.14), schemaWith("f", SchemaFieldType.NUMBER));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("NUMBER + Integer → no warning (integer is valid number)")
        void numberPlusInteger() {
            ValidationResult result = validate(Map.of("f", 42), schemaWith("f", SchemaFieldType.NUMBER));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("NUMBER + Long → no warning (integer is valid number)")
        void numberPlusLong() {
            ValidationResult result = validate(Map.of("f", 42L), schemaWith("f", SchemaFieldType.NUMBER));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("BOOLEAN + Boolean → no warning")
        void booleanPlusBoolean() {
            ValidationResult result = validate(Map.of("f", true), schemaWith("f", SchemaFieldType.BOOLEAN));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("OBJECT + Map → no warning")
        void objectPlusMap() {
            ValidationResult result = validate(Map.of("f", Map.of("k", "v")), schemaWith("f", SchemaFieldType.OBJECT));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("ARRAY + List → no warning")
        void arrayPlusList() {
            ValidationResult result = validate(Map.of("f", List.of("a", "b")), schemaWith("f", SchemaFieldType.ARRAY));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("FILE + String → no warning")
        void filePlusString() {
            ValidationResult result =
                    validate(Map.of("f", "@ef/suites/abc/file.txt"), schemaWith("f", SchemaFieldType.FILE));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Null value → no TYPE warning")
        void nullValueNoWarning() {
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("f", null);
            ValidationResult result = validate(data, schemaWith("f", SchemaFieldType.STRING));
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("No schema field → no TYPE warning")
        void noSchemaFieldNoWarning() {
            ValidationResult result = validate(Map.of("f", 42L), List.of());
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("Schema field with null type → no TYPE warning")
        void nullTypeNoWarning() {
            List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                    .name("f")
                    .type(null)
                    .required(false)
                    .build());
            ValidationResult result = validate(Map.of("f", 42L), schema);
            assertThat(hasTypeWarning(result, "f")).isFalse();
        }

        @Test
        @DisplayName("TYPE warning message format")
        void warningMessageFormat() {
            ValidationResult result = validate(Map.of("f", 42L), schemaWith("f", SchemaFieldType.STRING));
            ValidationWarningDto typeWarning = result.getWarnings().stream()
                    .filter(w -> w.getCode() == ValidationWarningCode.TYPE)
                    .findFirst()
                    .orElseThrow();
            assertThat(typeWarning.getFieldName()).isEqualTo("f");
            assertThat(typeWarning.getPath()).isEqualTo("$.data.f");
            assertThat(typeWarning.getMessage()).contains("STRING").contains("Long");
        }
    }
}
