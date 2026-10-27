package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SchemaValidationService")
class SchemaValidationServiceTest {

    private SchemaValidationService schemaValidationService;

    @BeforeEach
    void setUp() {
        ValidationProperties validationProperties = new ValidationProperties();
        validationProperties.setMaxWarningsPerCase(5);
        schemaValidationService = new SchemaValidationService(new ObjectMapper(), validationProperties);
        schemaValidationService.loadMetaSchema();
    }

    @Test
    @DisplayName("validate returns valid when data satisfies schema")
    void validate_validData_returnsValid() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "prompt", Map.of("type", "string"),
                                "expected", Map.of("type", "string")),
                "required", List.of("prompt", "expected"));
        Map<String, Object> data = Map.of("prompt", "hello", "expected", "world");

        ValidationResult result = schemaValidationService.validate(data, schema);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("validate returns invalid when required field is missing")
    void validate_missingRequired_returnsInvalid() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("prompt", Map.of("type", "string")),
                "required", List.of("prompt"));
        Map<String, Object> data = Map.of();

        ValidationResult result = schemaValidationService.validate(data, schema);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("validate returns invalid when field has wrong type")
    void validate_wrongType_returnsInvalid() {
        Map<String, Object> schema =
                Map.of("type", "object", "properties", Map.of("expected", Map.of("type", "string")));
        Map<String, Object> data = Map.of("expected", 42);

        ValidationResult result = schemaValidationService.validate(data, schema);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("validate produces multiple warnings for multiple violations")
    void validate_multipleViolations_producesMultipleWarnings() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "prompt", Map.of("type", "string"),
                                "expected", Map.of("type", "string")),
                "required", List.of("prompt", "expected"));
        Map<String, Object> data = Map.of();

        ValidationResult result = schemaValidationService.validate(data, schema);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("getSchemaValidationError returns empty for valid JSON Schema")
    void getSchemaValidationError_returnsEmptyForValidSchema() {
        Map<String, Object> validSchema = Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")));

        var error = schemaValidationService.getSchemaValidationError(validSchema);

        assertThat(error).isEmpty();
    }

    @Test
    @DisplayName("getSchemaValidationError returns error when schema contains $ref")
    void getSchemaValidationError_rejectsRef() {
        Map<String, Object> schemaWithRef =
                Map.of("$ref", "#/definitions/Model", "definitions", Map.of("Model", Map.of("type", "object")));

        var error = schemaValidationService.getSchemaValidationError(schemaWithRef);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("$ref not supported in v1");
    }

    @Test
    @DisplayName("getSchemaValidationError returns error when schema has invalid type")
    void getSchemaValidationError_rejectsInvalidType() {
        Map<String, Object> invalidTypeSchema =
                Map.of("type", "object", "properties", Map.of("x", Map.of("type", "abc")));

        var error = schemaValidationService.getSchemaValidationError(invalidTypeSchema);

        assertThat(error).isPresent();
        // json-schema-validator 3.x error message format: "does not have a value in the enumeration [...]"
        assertThat(error.get()).containsAnyOf("type", "enumeration");
    }

    @Test
    @DisplayName("getSchemaValidationError returns error when schema has unknown keyword (typo)")
    void getSchemaValidationError_rejectsUnknownKeyword() {
        Map<String, Object> schemaWithTypo =
                Map.of("type", "object", "properties2", Map.of("x", Map.of("type", "string")));

        var error = schemaValidationService.getSchemaValidationError(schemaWithTypo);

        assertThat(error).isPresent();
        assertThat(error.get()).containsIgnoringCase("properties2");
    }

    @Test
    @DisplayName("buildFieldSchema maps FILE and SKILL fields to JSON-schema type string")
    void buildFieldSchema_fileAndSkillFieldsAreStrings() {
        List<FieldDefinitionDto> fields = List.of(
                FieldDefinitionDto.builder()
                        .name("attachment")
                        .type(SchemaFieldType.FILE)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("skill")
                        .type(SchemaFieldType.SKILL)
                        .required(true)
                        .build());

        Map<String, Object> schema = SchemaValidationService.buildFieldSchema(fields);

        assertThat(schema.get("properties"))
                .isEqualTo(Map.of("attachment", Map.of("type", "string"), "skill", Map.of("type", "string")));
        assertThat(schema.get("required")).isEqualTo(List.of("skill"));
    }

    @Test
    @DisplayName("validate accepts a string for a SKILL field and rejects a number")
    void validate_skillField_acceptsStringRejectsNumber() {
        Map<String, Object> schema = SchemaValidationService.buildFieldSchema(List.of(FieldDefinitionDto.builder()
                .name("skill")
                .type(SchemaFieldType.SKILL)
                .build()));

        assertThat(schemaValidationService
                        .validate(Map.of("skill", "skills/my-bucket/summariser"), schema)
                        .isValid())
                .isTrue();
        assertThat(schemaValidationService.validate(Map.of("skill", 42), schema).isValid())
                .isFalse();
    }
}
