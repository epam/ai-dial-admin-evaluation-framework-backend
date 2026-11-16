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
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BlankFileDefaultTest {
    private final DialFileClient client = mock(DialFileClient.class);
    private final BindingTypeResolver typeResolver = new BindingTypeResolver();
    private final DialFileStorageProperties properties = new DialFileStorageProperties();
    private final DialFileRefResolver files = new DialFileRefResolver(client, properties, typeResolver);
    private final ResourceRefPlaceholderResolver resources =
            new ResourceRefPlaceholderResolver(files, new SkillRefResolver(typeResolver));
    private final TemplateVariableResolver variables = new TemplateVariableResolver();
    private final TemplateContentResolver content = new TemplateContentResolver(variables, resources);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonataSourcePreprocessor preprocessor = new JsonataSourcePreprocessor(variables, resources, mapper);
    private final List<FieldDefinitionDto> schema = List.of(
            FieldDefinitionDto.builder().name("doc").type(SchemaFieldType.FILE).build(),
            FieldDefinitionDto.builder().name("turnDoc").type(SchemaFieldType.FILE).perTurn(true).build());
    private final InputBindingDto binding = binding("doc", "doc");

    BlankFileDefaultTest() {
        properties.setBucketAlias("@ef");
    }

    private static InputBindingDto binding(String variable, String field) {
        return InputBindingDto.builder().templateVariable(variable).dataField(field).build();
    }

    private RequestResolver requestResolver() {
        final JsonataProperties jsonata = new JsonataProperties();
        jsonata.setEvaluationTimeoutMs(5000L);
        jsonata.setMaxRecursionDepth(500);
        return new RequestResolver(content, new RequestBodyEvaluator(
                content, preprocessor, new DashjoinJsonataEvaluationService(mapper, jsonata), mapper));
    }

    @Test
    @DisplayName("Blank FILE data selects a default for explicit and schema-inferred references")
    void blankFileFallbackMatrix() {
        for (final String blank : List.of("", " \t\n")) {
            assertThat(resources.resolve(blank, "FILE", binding, schema, () -> "public/no-file"))
                    .isEqualTo("files/public/no-file");
            assertThat(resources.resolve(blank, null, binding, schema, () -> "public/no-file"))
                    .isEqualTo("files/public/no-file");
        }
    }

    @Test
    @DisplayName("FILE fallback preserves validation, explicit constants, non-FILE and non-String values")
    void compatibilityMatrix() {
        final InputBindingDto constant = InputBindingDto.builder().templateVariable("doc")
                .dataField("doc").constantValue("").build();
        assertThatThrownBy(() -> resources.resolve("", "file", binding, schema, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resources.resolve("", "file", binding, schema, () -> ""))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resources.resolve("", "file", binding, schema, () -> "invalid/path"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resources.resolve("", "file", constant, schema, () -> "public/no-file"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resources.resolve(" public/a", "file", binding, schema, () -> "public/no-file"))
                .isInstanceOf(ValidationException.class);
        assertThat(resources.resolve("", "string", binding, schema, () -> "public/no-file")).isEqualTo("");
        assertThat(resources.resolve("", "skill", binding, schema, () -> "public/no-file")).isEqualTo("");
        for (final Object value : List.of(false, 0, List.of(), Map.of())) {
            assertThat(resources.resolve(value, "file", binding, schema, () -> "public/no-file")).isEqualTo(value);
        }
        assertThat(resources.resolve(null, "file", binding, schema, () -> "public/no-file")).isNull();
    }

    @Test
    @DisplayName("Nonblank FILE values are converted without evaluating their defaults")
    void nonblankDoesNotSelectDefault() {
        assertThat(resources.resolve("public/a", "file", binding, schema, () -> {
            throw new AssertionError("default must be lazy");
        })).isEqualTo("files/public/a");
    }

    @Test
    @DisplayName("JSONata preprocessing substitutes defaults in quoted and bare FILE positions")
    void preprocessBlankFileDefaults() {
        final String result = preprocessor.preprocess(
                "[\"${{doc:public/no-file}}\", ${{doc|file:public/no-file}}, \"x${{doc|file:public/no-file}}y\"]",
                Map.of("doc", binding), Map.of("doc", " \t"), new ArrayList<>(), schema);
        assertThat(result).isEqualTo("[\"files/public/no-file\", \"files/public/no-file\", \"x \\ty\"]");
    }

    @Test
    @DisplayName("Structural placeholders select FILE defaults but embedded and explicitly string placeholders remain blank")
    void structuralDefaultAndOptOut() {
        final Map<String, Object> body = Map.of("inferred", "${{doc:public/no-file}}", "explicit", "${{doc|FILE:public/no-file}}",
                "string", "${{doc|string:public/no-file}}", "embedded", "see ${{doc|file:public/no-file}}");
        assertThat(content.resolveObject(body, Map.of("doc", binding), Map.of("doc", ""), new ArrayList<>(), schema))
                .isEqualTo(Map.of("inferred", "files/public/no-file", "explicit", "files/public/no-file", "string", "", "embedded", "see "));
    }

    @Test
    @DisplayName("Effective shared and per-turn FILE values select defaults independently on each turn")
    void effectiveTurnData() {
        final RequestTemplateDto template = RequestTemplateDto.builder().body(JsonRequestBodyDto.builder()
                .content(Map.of("shared", "${{doc:public/no-file}}", "turn", "${{turn:public/no-file}}"))
                .build()).build();
        final List<InputBindingDto> bindings = List.of(binding, binding("turn", "turnDoc"));
        final ResolvedJsonBodyDto first = (ResolvedJsonBodyDto) requestResolver().resolveForRun(
                template, bindings, Map.of("doc", "", "turnDoc", "public/turn0"), Map.of(), schema).getBody();
        final ResolvedJsonBodyDto second = (ResolvedJsonBodyDto) requestResolver().resolveForRun(
                template, bindings, Map.of("doc", "", "turnDoc", " \n"), Map.of(), schema).getBody();
        assertThat(first.getContent()).containsEntry("shared", "files/public/no-file")
                .containsEntry("turn", "files/public/turn0");
        assertThat(second.getContent()).containsEntry("shared", "files/public/no-file")
                .containsEntry("turn", "files/public/no-file");
    }

    @Test
    @DisplayName("JSONata preprocesses blank quoted and bare FILE placeholders before filtering sentinels")
    void jsonataSentinelFilter() {
        final String expression = "{\"attachments\": [\"${{doc:public/no-file}}\", ${{turn|file:public/no-file}}, "
                + "\"${{a|file}}\", \"${{b|file}}\"][$not($contains($, 'no-file'))]}";
        final List<InputBindingDto> bindings = List.of(binding, binding("turn", "turnDoc"), binding("a", "a"), binding("b", "b"));
        final Map<String, Object> effectiveData = Map.of("doc", "", "turnDoc", " \t", "a", "public/a", "b", "public/b");
        final RequestTemplateDto template = RequestTemplateDto.builder().body(JsonRequestBodyDto.builder().jsonataContent(expression).build()).build();
        final ResolvedJsonBodyDto body = (ResolvedJsonBodyDto) requestResolver().resolveForRun(template, bindings, effectiveData, Map.of(), schema).getBody();
        assertThat(body.getContent()).containsEntry("attachments", List.of("files/public/a", "files/public/b"));
    }

    @Test
    @DisplayName("Multipart TEXT and explicit FILE values fall back; hintless FILE stays raw and converted FILE is rejected")
    void multipartBoundary() {
        final RequestTemplateDto template = RequestTemplateDto.builder().body(MultipartFormDataRequestBodyDto.builder().content(List.of(
                FormPartDto.builder().name("text-inferred").type(FormPartType.TEXT).value("${{doc:public/no-file}}").build(),
                FormPartDto.builder().name("text-explicit").type(FormPartType.TEXT).value("${{doc|file:public/no-file}}").build(),
                FormPartDto.builder().name("file-raw").type(FormPartType.FILE).value("${{doc:public/no-file}}").build(),
                FormPartDto.builder().name("file-explicit").type(FormPartType.FILE).value("${{doc|file:public/no-file}}").build())).build()).build();
        final ResolvedMultipartBodyDto body = (ResolvedMultipartBodyDto) requestResolver().resolve(template, List.of(binding), Map.of("doc", ""), schema).getBody();
        assertThat(body.getParts()).extracting(part -> part.getResolvedValue())
                .containsExactly("files/public/no-file", "files/public/no-file", "", "files/public/no-file");
        assertThatThrownBy(() -> new MultipartFormDataRequestBodySerializer(client, files).serialize(body))
                .isInstanceOf(ValidationException.class).hasMessageContaining("files/public/no-file");
    }

    @Test
    @DisplayName("MCP inferred FILE binding falls back for blank data and retains missing-value warnings")
    void mcpInferredFileAndMissing() {
        final McpRequestResolver mcp = new McpRequestResolver(resources);
        final ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("ref", "${{doc:public/no-file}}", "missing", "${{missing|file}}"));
        final McpRequestResolver.ResolutionResult result = mcp.resolve(template, List.of(binding), Map.of("doc", "\t"), schema);
        assertThat(result.getArguments()).containsEntry("ref", "files/public/no-file").containsEntry("missing", null);
        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().getFirst().getFieldName()).isEqualTo("missing");
    }

    @Test
    @DisplayName("MCP blank FILE defaults use typed parsing, direct lookup and constant precedence")
    void mcpDefaults() {
        final McpRequestResolver mcp = new McpRequestResolver(resources);
        final ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("ref", "${{doc|FILE:public/no-file}}", "number", "${{doc|file:7}}",
                "boolean", "${{doc|file:false}}", "embedded", "x${{doc|file:public/no-file}}y"));
        assertThat(mcp.resolve(template, null, Map.of("doc", ""), schema).getArguments())
                .containsEntry("ref", "files/public/no-file").containsEntry("number", 7L)
                .containsEntry("boolean", false).containsEntry("embedded", "xy");
        final InputBindingDto constant = InputBindingDto.builder().templateVariable("doc").constantValue("").build();
        assertThatThrownBy(() -> mcp.resolve(template, List.of(constant), Map.of("doc", "public/a"), schema))
                .isInstanceOf(ValidationException.class);
        final Map<String, Object> nullData = new HashMap<>();
        nullData.put("doc", null);
        assertThat(mcp.resolve(template, null, nullData, schema).getArguments())
                .containsEntry("ref", "files/public/no-file").containsEntry("number", 7L).containsEntry("boolean", false);
    }
}
