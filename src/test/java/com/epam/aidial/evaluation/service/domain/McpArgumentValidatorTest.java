package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class McpArgumentValidatorTest {

    private static final Map<String, Object> TOOL_SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of("repoName", Map.of("type", "string"), "branch", Map.of("type", "string")),
            "required",
            List.of("repoName"));

    private final McpArgumentValidator validator = new McpArgumentValidator(
            new TemplateVariableExtractor(), new JsonSchemaPropertyExtractor(new ObjectMapper()));

    @Test
    @DisplayName("Required argument absent from the template produces a REQUIRED warning")
    void shouldWarn_whenRequiredArgumentAbsent() {
        List<ValidationWarningDto> warnings = validator.validate(TOOL_SCHEMA, Map.of("branch", "main"), List.of());

        assertThat(warnings).singleElement().satisfies(w -> {
            assertThat(w.getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
            assertThat(w.getFieldName()).isEqualTo("repoName");
            assertThat(w.getPath()).isEqualTo("$.argumentTemplate.arguments");
        });
    }

    @Test
    @DisplayName("Required argument set to null produces a REQUIRED warning")
    void shouldWarn_whenRequiredArgumentIsNull() {
        List<ValidationWarningDto> warnings = validator.validate(TOOL_SCHEMA, arguments("repoName", null), List.of());

        assertThat(warnings).singleElement().satisfies(w -> {
            assertThat(w.getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
            assertThat(w.getFieldName()).isEqualTo("repoName");
        });
    }

    @Test
    @DisplayName("Required argument holding whitespace only produces a REQUIRED warning")
    void shouldWarn_whenRequiredArgumentIsWhitespaceOnly() {
        List<ValidationWarningDto> warnings = validator.validate(TOOL_SCHEMA, Map.of("repoName", "   "), List.of());

        assertThat(warnings).singleElement().satisfies(w -> {
            assertThat(w.getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
            assertThat(w.getFieldName()).isEqualTo("repoName");
        });
    }

    @Test
    @DisplayName("Required argument bound to a blank constant produces a REQUIRED warning")
    void shouldWarn_whenRequiredArgumentBoundToBlankConstant() {
        List<ValidationWarningDto> warnings =
                validator.validate(TOOL_SCHEMA, Map.of("repoName", "${{repo}}"), List.of(binding("repo", null, "")));

        assertThat(warnings).singleElement().satisfies(w -> {
            assertThat(w.getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
            assertThat(w.getFieldName()).isEqualTo("repoName");
        });
    }

    @Test
    @DisplayName("Required argument whose placeholder carries a blank default produces a REQUIRED warning")
    void shouldWarn_whenRequiredArgumentPlaceholderHasBlankDefault() {
        List<ValidationWarningDto> warnings =
                validator.validate(TOOL_SCHEMA, Map.of("repoName", "${{repo:}}"), List.of());

        assertThat(warnings).singleElement().satisfies(w -> {
            assertThat(w.getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
            assertThat(w.getFieldName()).isEqualTo("repoName");
        });
    }

    @Test
    @DisplayName("Required argument whose placeholder carries a non-blank default produces no warning")
    void shouldNotWarn_whenRequiredArgumentPlaceholderHasUsableDefault() {
        List<ValidationWarningDto> warnings =
                validator.validate(TOOL_SCHEMA, Map.of("repoName", "${{repo:main}}"), List.of());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("Required argument bound to a data field produces no warning")
    void shouldNotWarn_whenRequiredArgumentBoundToDataField() {
        List<ValidationWarningDto> warnings = validator.validate(
                TOOL_SCHEMA, Map.of("repoName", "${{repo}}"), List.of(binding("repo", "repository_name", null)));

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("Optional argument left empty produces no warning")
    void shouldNotWarn_whenOptionalArgumentIsEmpty() {
        List<ValidationWarningDto> warnings =
                validator.validate(TOOL_SCHEMA, Map.of("repoName", "dial", "branch", ""), List.of());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("Required argument holding a non-string constant produces no warning")
    void shouldNotWarn_whenRequiredArgumentIsNonStringConstant() {
        Map<String, Object> schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of("limit", Map.of("type", "integer")),
                "required",
                List.of("limit"));

        List<ValidationWarningDto> warnings = validator.validate(schema, Map.of("limit", 0), List.of());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("Absent tool schema produces no warnings")
    void shouldNotWarn_whenToolSchemaAbsent() {
        List<ValidationWarningDto> warnings = validator.validate(null, Map.of("repoName", ""), List.of());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("Tool schema without properties produces no warnings")
    void shouldNotWarn_whenToolSchemaHasNoProperties() {
        // The argument is empty on purpose: with a usable schema this would warn, so the assertion
        // only holds because the missing "properties" disables the check.
        List<ValidationWarningDto> warnings = validator.validate(
                Map.of("type", "object", "required", List.of("repoName")), Map.of("repoName", ""), List.of());

        assertThat(warnings).isEmpty();
    }

    private static Map<String, Object> arguments(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static InputBindingDto binding(String templateVariable, String dataField, Object constantValue) {
        return InputBindingDto.builder()
                .templateVariable(templateVariable)
                .dataField(dataField)
                .constantValue(constantValue)
                .build();
    }
}
