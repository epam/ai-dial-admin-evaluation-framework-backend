package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.FormPartDto;
import com.epam.aidial.evaluation.service.domain.dto.FormPartType;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableSource;
import com.epam.aidial.evaluation.service.domain.dto.UrlEncodedFormRequestBodyDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TemplateVariableExtractorTest {

    private final TemplateVariableExtractor extractor = new TemplateVariableExtractor();

    @Test
    void shouldExtractSimpleVariable() {
        var template = RequestTemplateDto.builder()
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{user_prompt}}"))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("user_prompt");
        assertThat(result.get(0).isHasDefault()).isFalse();
        assertThat(result.get(0).getDefaultValue()).isNull();
        assertThat(result.get(0).getSources()).containsExactly(TemplateVariableSource.BODY);
    }

    @Test
    void shouldExtractVariableWithDefault() {
        var template = RequestTemplateDto.builder()
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("temperature", "${{temp:0.7}}"))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("temp");
        assertThat(result.get(0).isHasDefault()).isTrue();
        assertThat(result.get(0).getDefaultValue()).isEqualTo("0.7");
    }

    @Test
    void shouldExtractMultipleVariablesInOneString() {
        var template = RequestTemplateDto.builder()
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("message", "Hello ${{name}}, your score is ${{score:0}}"))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("name");
        assertThat(result.get(0).isHasDefault()).isFalse();
        assertThat(result.get(1).getName()).isEqualTo("score");
        assertThat(result.get(1).isHasDefault()).isTrue();
        assertThat(result.get(1).getDefaultValue()).isEqualTo("0");
    }

    @Test
    void shouldExtractFromNestedBodyValues() {
        var template = RequestTemplateDto.builder()
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of(
                                "outer",
                                Map.of(
                                        "inner",
                                        "${{nested_var}}",
                                        "list",
                                        List.of("${{list_var:default}}", "literal"))))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(TemplateVariableExtractor.ExtractedVariable::getName)
                .containsExactlyInAnyOrder("nested_var", "list_var");
    }

    @Test
    void shouldTrackDuplicateVariableAcrossSectionsWithMultipleSources() {
        var template = RequestTemplateDto.builder()
                .urlTemplate("/api/${{model}}/completions")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("model", "${{model}}"))
                        .build())
                .queryParams(List.of(KeyValueTemplateDto.builder()
                        .key("model")
                        .value("${{model}}")
                        .build()))
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("model");
        assertThat(result.get(0).getSources())
                .containsExactlyInAnyOrder(
                        TemplateVariableSource.URL, TemplateVariableSource.BODY, TemplateVariableSource.QUERY);
    }

    @Test
    void shouldReturnEmptyForNoVariables() {
        var template = RequestTemplateDto.builder()
                .urlTemplate("/api/v1/completions")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("model", "gpt-4", "temperature", 0.7))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyForNullTemplate() {
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void shouldExtractFromUrlTemplate() {
        var template = RequestTemplateDto.builder()
                .urlTemplate("/api/v1/deployments/${{deployment_id}}/chat/completions")
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("deployment_id");
        assertThat(result.get(0).getSources()).containsExactly(TemplateVariableSource.URL);
    }

    @Test
    void shouldExtractFromQueryParams() {
        var template = RequestTemplateDto.builder()
                .queryParams(List.of(KeyValueTemplateDto.builder()
                        .key("api-version")
                        .value("${{api_version:2024-01-01}}")
                        .build()))
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("api_version");
        assertThat(result.get(0).isHasDefault()).isTrue();
        assertThat(result.get(0).getDefaultValue()).isEqualTo("2024-01-01");
        assertThat(result.get(0).getSources()).containsExactly(TemplateVariableSource.QUERY);
    }

    @Test
    void shouldExtractFromHeaders() {
        var template = RequestTemplateDto.builder()
                .headers(List.of(KeyValueTemplateDto.builder()
                        .key("X-Request-Id")
                        .value("${{request_id}}")
                        .build()))
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("request_id");
        assertThat(result.get(0).getSources()).containsExactly(TemplateVariableSource.HEADER);
    }

    @Test
    void shouldHandleMalformedSyntaxGracefully() {
        var template = RequestTemplateDto.builder()
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of(
                                "a", "${{valid}}",
                                "b", "${{}}", // empty variable name — no match
                                "c", "${missing}", // wrong syntax — no match
                                "d", "${{ }}", // whitespace-only name — trimmed to empty, skipped
                                "e", "${{also_valid:}}" // empty default
                                ))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result)
                .extracting(TemplateVariableExtractor.ExtractedVariable::getName)
                .containsExactlyInAnyOrder("valid", "also_valid");
        var alsoValid = result.stream()
                .filter(v -> v.getName().equals("also_valid"))
                .findFirst()
                .orElseThrow();
        assertThat(alsoValid.isHasDefault()).isTrue();
        assertThat(alsoValid.getDefaultValue()).isEmpty();
    }

    @Test
    void shouldHandleSameVariableMultipleTimesWithinOneSection() {
        var template = RequestTemplateDto.builder()
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of(
                                "field1", "${{model}}",
                                "field2", "${{model}}"))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("model");
        assertThat(result.get(0).getSources()).isEqualTo(Set.of(TemplateVariableSource.BODY));
    }

    @Test
    void shouldExtractFromAllFieldsSimultaneously() {
        var template = RequestTemplateDto.builder()
                .urlTemplate("/api/${{path_var}}/test")
                .queryParams(List.of(KeyValueTemplateDto.builder()
                        .key("q")
                        .value("${{query_var}}")
                        .build()))
                .headers(List.of(KeyValueTemplateDto.builder()
                        .key("H")
                        .value("${{header_var}}")
                        .build()))
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("b", "${{body_var}}"))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(4);
        assertThat(result)
                .extracting(TemplateVariableExtractor.ExtractedVariable::getName)
                .containsExactly("path_var", "query_var", "header_var", "body_var");
    }

    @Test
    void shouldHandleDefaultWithColonInValue() {
        var template = RequestTemplateDto.builder()
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("url", "${{base_url:http://example.com}}"))
                        .build())
                .build();

        var result = extractor.extract(template);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("base_url");
        assertThat(result.get(0).getDefaultValue()).isEqualTo("http://example.com");
    }

    @Nested
    class MultipartFormDataBodyExtraction {

        @Test
        void shouldExtractFromFormPartValue() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("field1")
                                    .type(FormPartType.TEXT)
                                    .value("${{user_input}}")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("user_input");
            assertThat(result.get(0).getSources()).containsExactly(TemplateVariableSource.BODY);
        }

        @Test
        void shouldExtractFromFormPartFilename() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("attachment")
                                    .type(FormPartType.FILE)
                                    .value("binary-data")
                                    .filename("${{upload_name}}.pdf")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("upload_name");
            assertThat(result.get(0).getSources()).containsExactly(TemplateVariableSource.BODY);
        }

        @Test
        void shouldExtractFromBothValueAndFilename() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("document")
                                    .type(FormPartType.FILE)
                                    .value("${{file_content}}")
                                    .filename("${{file_name}}")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(2);
            assertThat(result)
                    .extracting(TemplateVariableExtractor.ExtractedVariable::getName)
                    .containsExactlyInAnyOrder("file_content", "file_name");
        }

        @Test
        void shouldExtractFromMultipleParts() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(
                                    FormPartDto.builder()
                                            .name("prompt")
                                            .type(FormPartType.TEXT)
                                            .value("${{user_prompt}}")
                                            .build(),
                                    FormPartDto.builder()
                                            .name("config")
                                            .type(FormPartType.TEXT)
                                            .value("${{config_json}}")
                                            .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(2);
            assertThat(result)
                    .extracting(TemplateVariableExtractor.ExtractedVariable::getName)
                    .containsExactlyInAnyOrder("user_prompt", "config_json");
        }

        @Test
        void shouldDeduplicateSameVariableAcrossParts() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(
                                    FormPartDto.builder()
                                            .name("field1")
                                            .type(FormPartType.TEXT)
                                            .value("${{shared_var}}")
                                            .build(),
                                    FormPartDto.builder()
                                            .name("field2")
                                            .type(FormPartType.TEXT)
                                            .value("${{shared_var}}")
                                            .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("shared_var");
            assertThat(result.get(0).getSources()).isEqualTo(Set.of(TemplateVariableSource.BODY));
        }

        @Test
        void shouldExtractFromNestedMapInFormPartValue() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("data")
                                    .type(FormPartType.TEXT)
                                    .value(Map.of("nested", "${{nested_var}}"))
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("nested_var");
            assertThat(result.get(0).getSources()).containsExactly(TemplateVariableSource.BODY);
        }

        @Test
        void shouldSkipNullPartsInContent() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("field1")
                                    .type(FormPartType.TEXT)
                                    .value("${{valid_var}}")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("valid_var");
        }

        @Test
        void shouldHandleNullContentInMultipartBody() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(null)
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).isEmpty();
        }

        @Test
        void shouldTrackVariableFromMultipartBodyAndUrl() {
            var template = RequestTemplateDto.builder()
                    .urlTemplate("/api/${{shared}}/invoke")
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("param")
                                    .type(FormPartType.TEXT)
                                    .value("${{shared}}")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("shared");
            assertThat(result.get(0).getSources())
                    .containsExactlyInAnyOrder(TemplateVariableSource.URL, TemplateVariableSource.BODY);
        }

        @Test
        void shouldExtractVariableWithDefaultFromFormPart() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("config")
                                    .type(FormPartType.TEXT)
                                    .value("${{model:gpt-4}}")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("model");
            assertThat(result.get(0).isHasDefault()).isTrue();
            assertThat(result.get(0).getDefaultValue()).isEqualTo("gpt-4");
        }
    }

    @Nested
    class UrlEncodedFormBodyExtraction {

        @Test
        void shouldExtractFromUrlEncodedFormValue() {
            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("username")
                                    .value("${{user_name}}")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("user_name");
            assertThat(result.get(0).getSources()).containsExactly(TemplateVariableSource.BODY);
        }

        @Test
        void shouldExtractFromMultipleUrlEncodedEntries() {
            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(
                                    KeyValueTemplateDto.builder()
                                            .key("grant_type")
                                            .value("${{grant}}")
                                            .build(),
                                    KeyValueTemplateDto.builder()
                                            .key("scope")
                                            .value("${{scope_val:openid}}")
                                            .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(2);
            assertThat(result)
                    .extracting(TemplateVariableExtractor.ExtractedVariable::getName)
                    .containsExactlyInAnyOrder("grant", "scope_val");
            var scopeVar = result.stream()
                    .filter(v -> v.getName().equals("scope_val"))
                    .findFirst()
                    .orElseThrow();
            assertThat(scopeVar.isHasDefault()).isTrue();
            assertThat(scopeVar.getDefaultValue()).isEqualTo("openid");
        }

        @Test
        void shouldDeduplicateSameVariableInUrlEncodedEntries() {
            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(
                                    KeyValueTemplateDto.builder()
                                            .key("field1")
                                            .value("${{token}}")
                                            .build(),
                                    KeyValueTemplateDto.builder()
                                            .key("field2")
                                            .value("${{token}}")
                                            .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("token");
            assertThat(result.get(0).getSources()).isEqualTo(Set.of(TemplateVariableSource.BODY));
        }

        @Test
        void shouldHandleNullContentInUrlEncodedBody() {
            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder().content(null).build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).isEmpty();
        }

        @Test
        void shouldTrackVariableFromUrlEncodedBodyAndHeaders() {
            var template = RequestTemplateDto.builder()
                    .headers(List.of(KeyValueTemplateDto.builder()
                            .key("Authorization")
                            .value("Bearer ${{auth_token}}")
                            .build()))
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("token")
                                    .value("${{auth_token}}")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("auth_token");
            assertThat(result.get(0).getSources())
                    .containsExactlyInAnyOrder(TemplateVariableSource.HEADER, TemplateVariableSource.BODY);
        }

        @Test
        void shouldReturnEmptyForUrlEncodedBodyWithNoPlaceholders() {
            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("grant_type")
                                    .value("client_credentials")
                                    .build()))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Type Hint Parsing")
    class TypeHintParsing {

        @Test
        @DisplayName("Should extract variable with type hint")
        void shouldExtractVariableWithTypeHint() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("doc", "${{doc|file}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("doc");
            assertThat(result.get(0).getDeclaredType()).isEqualTo(SchemaFieldType.FILE);
            assertThat(result.get(0).isHasDefault()).isFalse();
        }

        @Test
        @DisplayName("Should extract variable with type hint and default")
        void shouldExtractVariableWithTypeHintAndDefault() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("ctx", "${{ctx|file:public/default-context.txt}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("ctx");
            assertThat(result.get(0).getDeclaredType()).isEqualTo(SchemaFieldType.FILE);
            assertThat(result.get(0).isHasDefault()).isTrue();
            assertThat(result.get(0).getDefaultValue()).isEqualTo("public/default-context.txt");
        }

        @Test
        @DisplayName("Should parse type hint case-insensitively")
        void shouldParseTypeHintCaseInsensitively() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of(
                                    "a", "${{a|FILE}}",
                                    "b", "${{b|File}}",
                                    "c", "${{c|file}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(3);
            assertThat(result).allMatch(v -> v.getDeclaredType() == SchemaFieldType.FILE);
        }

        @Test
        @DisplayName("Should parse all SchemaFieldType values as type hints")
        void shouldParseAllSchemaFieldTypes() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of(
                                    "a", "${{a|string}}",
                                    "b", "${{b|integer}}",
                                    "c", "${{c|number}}",
                                    "d", "${{d|boolean}}",
                                    "e", "${{e|object}}",
                                    "f", "${{f|array}}",
                                    "g", "${{g|file}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(7);
            assertThat(result.stream()
                            .filter(v -> "a".equals(v.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getDeclaredType())
                    .isEqualTo(SchemaFieldType.STRING);
            assertThat(result.stream()
                            .filter(v -> "b".equals(v.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getDeclaredType())
                    .isEqualTo(SchemaFieldType.INTEGER);
            assertThat(result.stream()
                            .filter(v -> "c".equals(v.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getDeclaredType())
                    .isEqualTo(SchemaFieldType.NUMBER);
            assertThat(result.stream()
                            .filter(v -> "d".equals(v.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getDeclaredType())
                    .isEqualTo(SchemaFieldType.BOOLEAN);
            assertThat(result.stream()
                            .filter(v -> "e".equals(v.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getDeclaredType())
                    .isEqualTo(SchemaFieldType.OBJECT);
            assertThat(result.stream()
                            .filter(v -> "f".equals(v.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getDeclaredType())
                    .isEqualTo(SchemaFieldType.ARRAY);
            assertThat(result.stream()
                            .filter(v -> "g".equals(v.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getDeclaredType())
                    .isEqualTo(SchemaFieldType.FILE);
        }

        @Test
        @DisplayName("Should allow default value containing pipe character")
        void shouldAllowDefaultWithPipeCharacter() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("q", "${{q|string:opt-a|opt-b}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("q");
            assertThat(result.get(0).getDeclaredType()).isEqualTo(SchemaFieldType.STRING);
            assertThat(result.get(0).isHasDefault()).isTrue();
            assertThat(result.get(0).getDefaultValue()).isEqualTo("opt-a|opt-b");
        }

        @Test
        @DisplayName("Should allow default value containing double-colon")
        void shouldAllowDefaultWithDoubleColon() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("query", "${{query|string:SELECT id::uuid FROM t}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("query");
            assertThat(result.get(0).getDeclaredType()).isEqualTo(SchemaFieldType.STRING);
            assertThat(result.get(0).getDefaultValue()).isEqualTo("SELECT id::uuid FROM t");
        }

        @Test
        @DisplayName("Should return null declaredType for plain variable (no type hint)")
        void shouldReturnNullDeclaredTypeForPlainVariable() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("prompt", "${{prompt}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDeclaredType()).isNull();
        }

        @Test
        @DisplayName("Should return null declaredType for variable with default but no type hint")
        void shouldReturnNullDeclaredTypeForVariableWithDefaultOnly() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("model", "${{model:gpt-4}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDeclaredType()).isNull();
            assertThat(result.get(0).isHasDefault()).isTrue();
            assertThat(result.get(0).getDefaultValue()).isEqualTo("gpt-4");
        }

        @Test
        @DisplayName("Should emit warning and set null declaredType for unrecognised type hint")
        void shouldEmitWarningForUnrecognisedTypeHint() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("doc", "${{doc|unknowntype}}"))
                            .build())
                    .build();

            var result = extractor.extractWithWarnings(template);

            assertThat(result.getVariables()).hasSize(1);
            assertThat(result.getVariables().get(0).getName()).isEqualTo("doc");
            assertThat(result.getVariables().get(0).getDeclaredType()).isNull();
            assertThat(result.getTypeHintWarnings()).hasSize(1);
            assertThat(result.getTypeHintWarnings().get(0))
                    .contains("unknowntype")
                    .contains("doc");
        }

        @Test
        @DisplayName("Should not emit warning for valid type hint")
        void shouldNotEmitWarningForValidTypeHint() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("doc", "${{doc|file}}"))
                            .build())
                    .build();

            var result = extractor.extractWithWarnings(template);

            assertThat(result.getVariables()).hasSize(1);
            assertThat(result.getTypeHintWarnings()).isEmpty();
        }

        @Test
        @DisplayName("Should extract variable with number type hint and default")
        void shouldExtractVariableWithNumberTypeAndDefault() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("temp", "${{temp|number:0.7}}"))
                            .build())
                    .build();

            var result = extractor.extract(template);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("temp");
            assertThat(result.get(0).getDeclaredType()).isEqualTo(SchemaFieldType.NUMBER);
            assertThat(result.get(0).isHasDefault()).isTrue();
            assertThat(result.get(0).getDefaultValue()).isEqualTo("0.7");
        }
    }

    @Nested
    @DisplayName("isPlaceholder")
    class IsPlaceholder {

        @Test
        @DisplayName("Simple placeholder returns true")
        void shouldReturnTrueForSimplePlaceholder() {
            assertThat(extractor.isPlaceholder("${{var}}")).isTrue();
        }

        @Test
        @DisplayName("Placeholder with file type hint returns true")
        void shouldReturnTrueForFileTypedPlaceholder() {
            assertThat(extractor.isPlaceholder("${{var|file}}")).isTrue();
        }

        @Test
        @DisplayName("Placeholder with string type hint returns true")
        void shouldReturnTrueForStringTypedPlaceholder() {
            assertThat(extractor.isPlaceholder("${{var|string}}")).isTrue();
        }

        @Test
        @DisplayName("Placeholder with default returns true")
        void shouldReturnTrueForPlaceholderWithDefault() {
            assertThat(extractor.isPlaceholder("${{var:default}}")).isTrue();
        }

        @Test
        @DisplayName("Placeholder with file type and default returns true")
        void shouldReturnTrueForFileTypedPlaceholderWithDefault() {
            assertThat(extractor.isPlaceholder("${{var|file:@ef/path}}")).isTrue();
        }

        @Test
        @DisplayName("Literal file ref returns false")
        void shouldReturnFalseForLiteralFileRef() {
            assertThat(extractor.isPlaceholder("@ef/suites/abc/file.csv")).isFalse();
        }

        @Test
        @DisplayName("Embedded placeholder returns false")
        void shouldReturnFalseForEmbeddedPlaceholder() {
            assertThat(extractor.isPlaceholder("some ${{var}} text")).isFalse();
        }

        @Test
        @DisplayName("Empty string returns false")
        void shouldReturnFalseForEmptyString() {
            assertThat(extractor.isPlaceholder("")).isFalse();
        }

        @Test
        @DisplayName("Null returns false")
        void shouldReturnFalseForNull() {
            assertThat(extractor.isPlaceholder(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("extractFromArgumentTemplateWithWarnings")
    class ExtractFromArgumentTemplateWithWarnings {

        @Test
        @DisplayName("Should return variables and empty warnings for valid type hints")
        void shouldReturnVariablesWithNoWarningsForValidHints() {
            var argTemplate = ArgumentTemplateDto.builder()
                    .arguments(Map.of("doc", "${{doc|file}}"))
                    .build();

            var result = extractor.extractFromArgumentTemplateWithWarnings(argTemplate);

            assertThat(result.getVariables()).hasSize(1);
            assertThat(result.getVariables().get(0).getName()).isEqualTo("doc");
            assertThat(result.getVariables().get(0).getDeclaredType()).isEqualTo(SchemaFieldType.FILE);
            assertThat(result.getTypeHintWarnings()).isEmpty();
        }

        @Test
        @DisplayName("Should return type hint warning for unrecognised type")
        void shouldReturnTypeHintWarningForUnrecognisedType() {
            var argTemplate = ArgumentTemplateDto.builder()
                    .arguments(Map.of("data", "${{input|unknown_type}}"))
                    .build();

            var result = extractor.extractFromArgumentTemplateWithWarnings(argTemplate);

            assertThat(result.getVariables()).hasSize(1);
            assertThat(result.getVariables().get(0).getDeclaredType()).isNull();
            assertThat(result.getTypeHintWarnings()).hasSize(1);
            assertThat(result.getTypeHintWarnings().get(0))
                    .contains("unknown_type")
                    .contains("input");
        }

        @Test
        @DisplayName("Should return empty for null argument template")
        void shouldReturnEmptyForNullTemplate() {
            var result = extractor.extractFromArgumentTemplateWithWarnings(null);

            assertThat(result.getVariables()).isEmpty();
            assertThat(result.getTypeHintWarnings()).isEmpty();
        }

        @Test
        @DisplayName("extractFromArgumentTemplate delegates to WithWarnings and returns variables only")
        void shouldDelegateToWithWarningsAndReturnVariablesOnly() {
            var argTemplate = ArgumentTemplateDto.builder()
                    .arguments(Map.of("query", "${{q}}", "data", "${{d|unknown}}"))
                    .build();

            var variables = extractor.extractFromArgumentTemplate(argTemplate);

            assertThat(variables).hasSize(2);
            // Verify warnings are not returned — only variables
            assertThat(variables)
                    .extracting(TemplateVariableExtractor.ExtractedVariable::getName)
                    .containsExactlyInAnyOrder("q", "d");
        }
    }
}
