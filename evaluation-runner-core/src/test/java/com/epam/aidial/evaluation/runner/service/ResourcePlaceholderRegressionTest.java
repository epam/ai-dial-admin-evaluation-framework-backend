package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.client.dialcore.SkillRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Empty resource placeholder regression")
class ResourcePlaceholderRegressionTest {

    private final BindingTypeResolver bindingTypeResolver = new BindingTypeResolver();
    private final TemplateVariableResolver variableResolver = new TemplateVariableResolver();
    private final DialFileStorageProperties storageProperties = new DialFileStorageProperties();
    private final JsonataProperties jsonataProperties = new JsonataProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateContentResolver contentResolver;
    private final RequestBodyEvaluator bodyEvaluator;
    private final McpRequestResolver mcpResolver;

    ResourcePlaceholderRegressionTest() {
        storageProperties.setBucketAlias("@ef");
        jsonataProperties.setEvaluationTimeoutMs(5000L);
        jsonataProperties.setMaxRecursionDepth(500);
        final ResourceRefPlaceholderResolver resourceResolver = new ResourceRefPlaceholderResolver(
                new DialFileRefResolver(mock(DialFileClient.class), storageProperties, bindingTypeResolver),
                new SkillRefResolver(bindingTypeResolver));
        contentResolver = new TemplateContentResolver(variableResolver, resourceResolver);
        bodyEvaluator = new RequestBodyEvaluator(
                contentResolver,
                new JsonataSourcePreprocessor(variableResolver, resourceResolver, objectMapper),
                new DashjoinJsonataEvaluationService(objectMapper, jsonataProperties),
                objectMapper);
        mcpResolver = new McpRequestResolver(resourceResolver);
    }

    enum Path {
        STRUCTURAL,
        JSONATA,
        MCP
    }

    static Stream<Arguments> resourceCases() {
        return Stream.of(Path.values())
                .flatMap(path -> Stream.of(SchemaFieldType.FILE, SchemaFieldType.SKILL)
                        .flatMap(type -> Stream.of(true, false).map(explicit -> Arguments.of(path, type, explicit))));
    }

    @ParameterizedTest
    @MethodSource("resourceCases")
    @DisplayName("Empty FILE and SKILL data uses the default for explicit and schema-derived types")
    void emptyResourceUsesDefault(Path path, SchemaFieldType type, boolean explicit) {
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        final String defaultValue = type == SchemaFieldType.FILE ? "public/no-file" : "skills/public/no-skill/";
        final String placeholder = placeholder(type, explicit, ":" + defaultValue);
        final Object result = resolve(path, placeholder, binding(), Map.of("FIELD", ""), schema(type), warnings);

        assertThat(result).isEqualTo(type == SchemaFieldType.FILE ? "files/public/no-file" : "skills/public/no-skill");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("resourceCases")
    @DisplayName("Empty FILE and SKILL data without a default resolves to null with a REQUIRED warning")
    void emptyResourceWithoutDefaultWarns(Path path, SchemaFieldType type, boolean explicit) {
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        final Object result =
                resolve(path, placeholder(type, explicit, ""), binding(), Map.of("FIELD", ""), schema(type), warnings);

        assertThat(result).isNull();
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
            assertThat(warning.getMessage()).contains("value");
        });
    }

    @ParameterizedTest
    @MethodSource("resourceCases")
    @DisplayName("An empty FILE or SKILL constant uses the default rather than the bound data")
    void emptyResourceConstantUsesDefault(Path path, SchemaFieldType type, boolean explicit) {
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        final InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("value")
                .dataField("FIELD")
                .constantValue("")
                .build();
        final String defaultValue = type == SchemaFieldType.FILE ? "public/no-file" : "skills/public/no-skill";
        final Object result = resolve(
                path,
                placeholder(type, explicit, ":" + defaultValue),
                binding,
                Map.of("FIELD", "public/should-not-win"),
                schema(type),
                warnings);

        assertThat(result).isEqualTo(type == SchemaFieldType.FILE ? "files/public/no-file" : "skills/public/no-skill");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @DisplayName("Empty ordinary strings remain values even with a default")
    void emptyStringIsPreserved(Path path) {
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        final Object result = resolve(
                path, "${{value:fallback}}", binding(), Map.of("FIELD", ""), schema(SchemaFieldType.STRING), warnings);

        assertThat(result).isEqualTo("");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @DisplayName("An explicit non-resource hint preserves an empty schema-bound FILE value")
    void explicitStringOverridesFileSchema(Path path) {
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        final Object result = resolve(
                path,
                "${{value|string:fallback}}",
                binding(),
                Map.of("FIELD", ""),
                schema(SchemaFieldType.FILE),
                warnings);

        assertThat(result).isEqualTo("");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @DisplayName("Whitespace-only FILE values still fail reference validation instead of using the default")
    void whitespaceFileIsNotAbsent(Path path) {
        assertThatThrownBy(() -> resolve(
                        path,
                        "${{value|file:public/no-file}}",
                        binding(),
                        Map.of("FIELD", " "),
                        schema(SchemaFieldType.FILE),
                        new ArrayList<>()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Disallowed file reference prefix");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @DisplayName("Embedded resource placeholders keep empty-string interpolation rather than using defaults")
    void embeddedResourceIsUnchanged(Path path) {
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        final Object result = resolve(
                path,
                "prefix-${{value|file:public/no-file}}",
                binding(),
                Map.of("FIELD", ""),
                schema(SchemaFieldType.FILE),
                warnings);

        assertThat(result).isEqualTo("prefix-");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = SchemaFieldType.class,
            names = {"FILE", "SKILL"})
    @DisplayName("MCP direct-name lookup applies resource defaults to empty values without bindings")
    void mcpDirectLookupUsesDefault(SchemaFieldType type) {
        final String defaultValue = type == SchemaFieldType.FILE ? "public/no-file" : "skills/public/no-skill";
        final ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("value", placeholder(type, true, ":" + defaultValue)));

        final McpRequestResolver.ResolutionResult result =
                mcpResolver.resolve(template, null, Map.of("value", ""), null);

        assertThat(result.getArguments())
                .containsEntry(
                        "value", type == SchemaFieldType.FILE ? "files/public/no-file" : "skills/public/no-skill");
        assertThat(result.getWarnings()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @DisplayName("Whitespace-only SKILL values remain unchanged instead of selecting defaults")
    void whitespaceSkillIsNotAbsent(Path path) {
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        final Object result = resolve(
                path,
                "${{value|skill:skills/public/no-skill}}",
                binding(),
                Map.of("FIELD", " "),
                schema(SchemaFieldType.SKILL),
                warnings);

        assertThat(result).isEqualTo(" ");
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("JSONata attachment filtering removes default sentinels for empty optional files")
    void optionalAttachmentsAreFilteredAfterDefaults() {
        final String source = """
            {"stream": true, "messages": [{"role": "user", "content": "${{user_message}}",
              "custom_content": {"attachments": [["${{file1|file}}", "${{file2|file}}",
                "${{file3|file:public/no-file}}", "${{file4|file:public/no-file}}",
                "${{file5|file:public/no-file}}"][$not($contains($, "no-file"))].{"url": $}]}}]}
            """;
        final Map<String, InputBindingDto> bindings = Map.of(
                "user_message",
                        InputBindingDto.builder()
                                .templateVariable("user_message")
                                .dataField("QUESTION")
                                .build(),
                "file1",
                        InputBindingDto.builder()
                                .templateVariable("file1")
                                .dataField("FILE_1")
                                .build(),
                "file2",
                        InputBindingDto.builder()
                                .templateVariable("file2")
                                .dataField("FILE_2")
                                .build(),
                "file3",
                        InputBindingDto.builder()
                                .templateVariable("file3")
                                .dataField("FILE_3")
                                .build(),
                "file4",
                        InputBindingDto.builder()
                                .templateVariable("file4")
                                .dataField("FILE_4")
                                .build(),
                "file5",
                        InputBindingDto.builder()
                                .templateVariable("file5")
                                .dataField("FILE_5")
                                .build());
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        final Map<String, Object> result = bodyEvaluator.evaluate(
                null,
                source,
                bindings,
                Map.of(
                        "QUESTION",
                        "Compare files",
                        "FILE_1",
                        "public/budget.xlsx",
                        "FILE_2",
                        "public/actuals.xlsx",
                        "FILE_3",
                        "",
                        "FILE_4",
                        "",
                        "FILE_5",
                        ""),
                Map.of(),
                warnings,
                null);

        assertThat(result)
                .isEqualTo(Map.of(
                        "stream",
                        true,
                        "messages",
                        List.of(Map.of(
                                "role",
                                "user",
                                "content",
                                "Compare files",
                                "custom_content",
                                Map.of(
                                        "attachments",
                                        List.of(
                                                Map.of("url", "files/public/budget.xlsx"),
                                                Map.of("url", "files/public/actuals.xlsx")))))));
        assertThat(warnings).isEmpty();
    }

    private Object resolve(
            Path path,
            String placeholder,
            InputBindingDto binding,
            Map<String, Object> data,
            List<FieldDefinitionDto> schema,
            List<ValidationWarningDto> warnings) {
        final Map<String, InputBindingDto> bindings = Map.of("value", binding);
        return switch (path) {
            case STRUCTURAL -> contentResolver.resolveObject(placeholder, bindings, data, warnings, schema);
            case JSONATA ->
                bodyEvaluator
                        .evaluate(
                                null,
                                objectMapper.writeValueAsString(Map.of("value", placeholder)),
                                bindings,
                                data,
                                Map.of(),
                                warnings,
                                schema)
                        .get("value");
            case MCP -> {
                final ArgumentTemplateDto template = new ArgumentTemplateDto();
                template.setArguments(Map.of("value", placeholder));
                final McpRequestResolver.ResolutionResult result =
                        mcpResolver.resolve(template, List.of(binding), data, schema);
                warnings.addAll(result.getWarnings());
                yield result.getArguments().get("value");
            }
        };
    }

    private static InputBindingDto binding() {
        return InputBindingDto.builder()
                .templateVariable("value")
                .dataField("FIELD")
                .build();
    }

    private static List<FieldDefinitionDto> schema(SchemaFieldType type) {
        return List.of(FieldDefinitionDto.builder().name("FIELD").type(type).build());
    }

    private static String placeholder(SchemaFieldType type, boolean explicit, String suffix) {
        return "${{value" + (explicit ? "|" + type.name().toLowerCase(Locale.ROOT) : "") + suffix + "}}";
    }
}
