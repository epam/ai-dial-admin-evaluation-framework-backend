package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedFormPartDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedUrlEncodedBodyDto;
import com.epam.aidial.evaluation.runner.dto.UrlEncodedFormRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers {@link RequestResolver}'s preview ({@link RequestResolver#resolve}) resolution path: URL/query
 * param/header/JSON-body/multipart/url-encoded-body placeholder resolution, type-hint (FILE ref)
 * handling, and the JSON body's real JSONata evaluation (per the {@code jsonata-request-templates}
 * change). Wired by hand (rather than {@code @InjectMocks}) with a real
 * {@link TemplateContentResolver}/{@link JsonataSourcePreprocessor}/{@link DashjoinJsonataEvaluationService}
 * chain so the JSON-body assertions exercise genuine JSONata evaluation rather than a canned stub — a
 * plain JSON object is a syntactic subset of JSONata and evaluates back to itself, so the existing
 * behavioral assertions (written against the pre-JSONata structural resolution) stay valid. The
 * DB-backed Try-It-Out overload ({@code ResolvedRequestService.resolveRequest}) that delegates here is
 * covered by the EF backend's own {@code ResolvedRequestServiceTest}.
 */
@DisplayName("RequestResolver")
@ExtendWith(MockitoExtension.class)
class RequestResolverTest {

    @Mock
    private TemplateVariableResolver templateVariableResolver;

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    private RequestResolver service;

    @BeforeEach
    void setUp() {
        TemplateContentResolver templateContentResolver =
                new TemplateContentResolver(templateVariableResolver, dialFileRefResolver);
        ObjectMapper objectMapper = new ObjectMapper();
        JsonataProperties jsonataProperties = new JsonataProperties();
        jsonataProperties.setEvaluationTimeoutMs(5000L);
        jsonataProperties.setMaxRecursionDepth(500);
        JsonataEvaluationService jsonataEvaluationService =
                new DashjoinJsonataEvaluationService(objectMapper, jsonataProperties);
        JsonataSourcePreprocessor jsonataSourcePreprocessor =
                new JsonataSourcePreprocessor(templateVariableResolver, dialFileRefResolver, objectMapper);
        RequestBodyEvaluator requestBodyEvaluator = new RequestBodyEvaluator(
                templateContentResolver, jsonataSourcePreprocessor, jsonataEvaluationService, objectMapper);
        service = new RequestResolver(templateContentResolver, requestBodyEvaluator);
    }

    @Nested
    @DisplayName("JSON body resolution")
    class JsonBodyResolution {

        @Test
        void shouldResolveSimplePlaceholderInJsonBody() {
            when(templateVariableResolver.resolveVariable(eq("user_prompt"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("Hello world");

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("prompt", "${{user_prompt}}"))
                            .build())
                    .build();
            var bindings = List.of(InputBindingDto.builder()
                    .templateVariable("user_prompt")
                    .dataField("promptField")
                    .build());
            var data = Map.<String, Object>of("promptField", "Hello world");

            ResolvedRequestDto result = service.resolve(template, bindings, data);

            assertThat(result.getBody()).isInstanceOf(ResolvedJsonBodyDto.class);
            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).containsEntry("prompt", "Hello world");
        }

        @Test
        void shouldResolveMultiplePlaceholdersInJsonBody() {
            when(templateVariableResolver.resolveVariable(eq("model"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("gpt-4");
            when(templateVariableResolver.resolveVariable(eq("temp"), eq("0.7"), any(), anyMap(), anyList()))
                    .thenReturn("0.7");

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of(
                                    "model", "${{model}}",
                                    "temperature", "${{temp:0.7}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).containsEntry("model", "gpt-4");
            assertThat(jsonBody.getContent()).containsEntry("temperature", "0.7");
        }

        @Test
        void shouldPreserveTypedValueForFullPlaceholderInJsonBody() {
            when(templateVariableResolver.resolveVariable(eq("temperature"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(0.7);

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("temperature", "${{temperature}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent().get("temperature")).isEqualTo(0.7);
            assertThat(jsonBody.getContent().get("temperature")).isInstanceOf(Double.class);
        }

        @Test
        void shouldResolveNestedObjectsInJsonBody() {
            when(templateVariableResolver.resolveVariable(eq("inner_val"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("resolved_inner");

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("outer", Map.of("inner", "${{inner_val}}")))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            @SuppressWarnings("unchecked")
            var outer = (Map<String, Object>) jsonBody.getContent().get("outer");
            assertThat(outer).containsEntry("inner", "resolved_inner");
        }

        @Test
        void shouldHandleNullBodyContent() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder().content(null).build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            assertThat(result.getBody()).isInstanceOf(ResolvedJsonBodyDto.class);
            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).isNull();
        }

        @Test
        @DisplayName("a body with neither content nor jsonataContent set resolves to content = null, no warnings")
        void shouldResolveNullContentWhenNeitherFieldSet() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder().build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            assertThat(result.getBody()).isInstanceOf(ResolvedJsonBodyDto.class);
            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).isNull();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        void shouldReturnNullBodyWhenTemplateHasNoBody() {
            var template =
                    RequestTemplateDto.builder().urlTemplate("/api/v1/test").build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            assertThat(result.getBody()).isNull();
        }

        @Test
        void shouldStringifyWhenPlaceholderIsEmbeddedInJsonBody() {
            when(templateVariableResolver.resolveVariable(eq("name"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("Alice");
            when(templateVariableResolver.resolveVariable(eq("score"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("95");

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("message", "Hello ${{name}}, your score is ${{score}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent().get("message")).isEqualTo("Hello Alice, your score is 95");
        }

        @Test
        void shouldHandleListValuesInJsonBody() {
            when(templateVariableResolver.resolveVariable(eq("item"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("resolved_item");

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("items", List.of("${{item}}", "literal")))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            @SuppressWarnings("unchecked")
            var items = (List<Object>) jsonBody.getContent().get("items");
            assertThat(items).containsExactly("resolved_item", "literal");
        }

        @Test
        void shouldReturnWarningWhenNullTemplate() {
            ResolvedRequestDto result = service.resolve(null, List.of(), Map.of());

            assertThat(result.getWarnings()).hasSize(1);
            assertThat(result.getWarnings().get(0).getMessage()).contains("No request template configured");
            assertThat(result.getBody()).isNull();
        }

        @Test
        void shouldPreserveLiteralsInJsonBody() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("model", "gpt-4", "temperature", 0.7, "stream", true))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).containsEntry("model", "gpt-4");
            assertThat(jsonBody.getContent()).containsEntry("temperature", 0.7);
            assertThat(jsonBody.getContent()).containsEntry("stream", true);
        }

        @Test
        void shouldDowngradeJsonataEvaluationFailureToRequestBodyEvaluationErrorWarningWithNullContent() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .jsonataContent("choices[0.message.content")
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).isNull();
            assertThat(result.getWarnings())
                    .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_EVALUATION_ERROR
                            && "$.requestTemplate.body".equals(w.getPath()));
        }
    }

    @Nested
    @DisplayName("Type-hint placeholder resolution")
    class TypeHintResolution {

        @Test
        @DisplayName("${{var|file}} resolves bound value to DIAL ref format (full-value path)")
        void shouldResolveTypeHintedPlaceholderToValue() {
            String shortRef = "@ef/suites/abc/file.bin";
            String dialRef = "files/real-bucket/suites/abc/file.bin";
            when(templateVariableResolver.resolveVariable(eq("some_prop"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(shortRef);
            when(dialFileRefResolver.resolveToDialRef(shortRef)).thenReturn(dialRef);

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("document", "${{some_prop|file}}"))
                            .build())
                    .build();
            var binding = InputBindingDto.builder()
                    .templateVariable("some_prop")
                    .dataField("docField")
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(binding), Map.of("docField", shortRef));

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).containsEntry("document", dialRef);
        }

        @Test
        @DisplayName("${{var|file:default}} falls back to default and resolves to DIAL ref")
        void shouldFallBackToDefaultForTypeHintedPlaceholder() {
            String shortRef = "public/default.txt";
            String dialRef = "files/public/default.txt";
            when(templateVariableResolver.resolveVariable(eq("doc"), eq(shortRef), any(), anyMap(), anyList()))
                    .thenReturn(shortRef);
            when(dialFileRefResolver.resolveToDialRef(shortRef)).thenReturn(dialRef);

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("file_ref", "${{doc|file:public/default.txt}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).containsEntry("file_ref", dialRef);
        }

        @Test
        @DisplayName("${{var}} without type hint still resolves correctly")
        void shouldResolveNonTypeHintedPlaceholderUnchanged() {
            when(templateVariableResolver.resolveVariable(eq("plain_var"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("plain-value");

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("field", "${{plain_var}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).containsEntry("field", "plain-value");
        }

        @Test
        @DisplayName("${{var:default}} without type hint still resolves correctly")
        void shouldResolveNonTypeHintedWithDefaultUnchanged() {
            when(templateVariableResolver.resolveVariable(eq("temp"), eq("0.7"), any(), anyMap(), anyList()))
                    .thenReturn(0.7);

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("temperature", "${{temp:0.7}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent().get("temperature")).isEqualTo(0.7);
        }

        @Test
        @DisplayName("${{var|string}} in embedded interpolation strips type hint correctly")
        void shouldStripTypeHintInEmbeddedInterpolation() {
            when(templateVariableResolver.resolveVariable(eq("name"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("Alice");

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("greeting", "Hello ${{name|string}}!"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent().get("greeting")).isEqualTo("Hello Alice!");
        }

        @Test
        @DisplayName("${{var|file}} preserves typed value in full-value path")
        void shouldPreserveTypedValueForTypeHintedFullPlaceholder() {
            var arrayValue = List.of("a", "b", "c");
            when(templateVariableResolver.resolveVariable(eq("items"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(arrayValue);

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("data", "${{items|array}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent().get("data")).isEqualTo(arrayValue);
        }

        @Test
        @DisplayName("${{var|file}} resolves to DIAL ref format for JSON body")
        void shouldResolveFileTypedPlaceholderToDialRef() {
            String shortRef = "@ef/suites/abc/doc.pdf";
            String dialRef = "files/real-bucket/suites/abc/doc.pdf";
            when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(shortRef);
            when(dialFileRefResolver.resolveToDialRef(shortRef)).thenReturn(dialRef);

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("attachment", "${{doc|file}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent()).containsEntry("attachment", dialRef);
        }

        @Test
        @DisplayName("${{var|file}} nested inside JSON object is resolved to DIAL ref")
        void shouldResolveFileTypedPlaceholderNestedInJsonObject() {
            String shortRef = "@ef/suites/abc/report.pdf";
            String dialRef = "files/real-bucket/suites/abc/report.pdf";
            when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(shortRef);
            when(dialFileRefResolver.resolveToDialRef(shortRef)).thenReturn(dialRef);

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("outer", Map.of("key", "${{doc|file}}")))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            @SuppressWarnings("unchecked")
            var outer = (Map<String, Object>) jsonBody.getContent().get("outer");
            assertThat(outer).containsEntry("key", dialRef);
        }

        @Test
        @DisplayName("${{var|file}} non-string resolved value is not passed to resolveToDialRef")
        void shouldNotResolveNonStringFileTypedValue() {
            var mapValue = Map.of("key", "value");
            when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(mapValue);

            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("attachment", "${{doc|file}}"))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var jsonBody = (ResolvedJsonBodyDto) result.getBody();
            assertThat(jsonBody.getContent().get("attachment")).isEqualTo(mapValue);
        }
    }

    @Nested
    @DisplayName("Multipart body resolution")
    class MultipartBodyResolution {

        @Test
        void shouldResolveTextPartValue() {
            when(templateVariableResolver.resolveVariable(eq("user_input"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("resolved text");

            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("prompt")
                                    .type(FormPartType.TEXT)
                                    .value("${{user_input}}")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            assertThat(result.getBody()).isInstanceOf(ResolvedMultipartBodyDto.class);
            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            assertThat(multipartBody.getParts()).hasSize(1);
            assertThat(multipartBody.getParts().get(0).getName()).isEqualTo("prompt");
            assertThat(multipartBody.getParts().get(0).getType()).isEqualTo(FormPartType.TEXT);
            assertThat(multipartBody.getParts().get(0).getResolvedValue()).isEqualTo("resolved text");
        }

        @Test
        void shouldResolveFilePartFilename() {
            when(templateVariableResolver.resolveVariable(eq("doc_name"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("report");

            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("file")
                                    .type(FormPartType.FILE)
                                    .value("binary-content")
                                    .filename("${{doc_name}}.pdf")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            assertThat(multipartBody.getParts()).hasSize(1);
            assertThat(multipartBody.getParts().get(0).getFilename()).isEqualTo("report.pdf");
        }

        @Test
        void shouldResolveBothValueAndFilename() {
            when(templateVariableResolver.resolveVariable(eq("content"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("file bytes");
            when(templateVariableResolver.resolveVariable(eq("fname"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("output");

            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("upload")
                                    .type(FormPartType.FILE)
                                    .value("${{content}}")
                                    .filename("${{fname}}.txt")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            ResolvedFormPartDto part = multipartBody.getParts().get(0);
            assertThat(part.getResolvedValue()).isEqualTo("file bytes");
            assertThat(part.getFilename()).isEqualTo("output.txt");
        }

        @Test
        void shouldResolveMultiplePartsIndependently() {
            when(templateVariableResolver.resolveVariable(eq("prompt_val"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("What is AI?");
            when(templateVariableResolver.resolveVariable(eq("context_val"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("technical");

            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(
                                    FormPartDto.builder()
                                            .name("prompt")
                                            .type(FormPartType.TEXT)
                                            .value("${{prompt_val}}")
                                            .build(),
                                    FormPartDto.builder()
                                            .name("context")
                                            .type(FormPartType.TEXT)
                                            .value("${{context_val}}")
                                            .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            assertThat(multipartBody.getParts()).hasSize(2);
            assertThat(multipartBody.getParts().get(0).getResolvedValue()).isEqualTo("What is AI?");
            assertThat(multipartBody.getParts().get(1).getResolvedValue()).isEqualTo("technical");
        }

        @Test
        void shouldPreserveTypedValueForFullPlaceholderInMultipartPart() {
            when(templateVariableResolver.resolveVariable(eq("json_data"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(Map.of("key", "value"));

            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("metadata")
                                    .type(FormPartType.TEXT)
                                    .value("${{json_data}}")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            assertThat(multipartBody.getParts().get(0).getResolvedValue()).isEqualTo(Map.of("key", "value"));
        }

        @Test
        void shouldHandleNullContentInMultipartBody() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(null)
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            assertThat(multipartBody.getParts()).isEmpty();
        }

        @Test
        void shouldHandlePartWithNullValueAndNullFilename() {
            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("empty")
                                    .type(FormPartType.TEXT)
                                    .value(null)
                                    .filename(null)
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            assertThat(multipartBody.getParts()).hasSize(1);
            assertThat(multipartBody.getParts().get(0).getResolvedValue()).isNull();
            assertThat(multipartBody.getParts().get(0).getFilename()).isNull();
        }

        @Test
        void shouldResolveNestedMapInFormPartValue() {
            when(templateVariableResolver.resolveVariable(eq("nested_val"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("inner_resolved");

            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("data")
                                    .type(FormPartType.TEXT)
                                    .value(Map.of("level1", Map.of("level2", "${{nested_val}}")))
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            @SuppressWarnings("unchecked")
            var level1 = (Map<String, Object>)
                    ((Map<String, Object>) multipartBody.getParts().get(0).getResolvedValue()).get("level1");
            assertThat(level1).containsEntry("level2", "inner_resolved");
        }

        @Test
        void shouldResolveMultipartWithBindingsFromData() {
            var binding = InputBindingDto.builder()
                    .templateVariable("user_input")
                    .dataField("inputCol")
                    .build();
            when(templateVariableResolver.resolveVariable(eq("user_input"), isNull(), eq(binding), anyMap(), anyList()))
                    .thenReturn("data-driven value");

            var template = RequestTemplateDto.builder()
                    .body(MultipartFormDataRequestBodyDto.builder()
                            .content(List.of(FormPartDto.builder()
                                    .name("input")
                                    .type(FormPartType.TEXT)
                                    .value("${{user_input}}")
                                    .build()))
                            .build())
                    .build();
            var bindings = List.of(binding);
            var data = Map.<String, Object>of("inputCol", "data-driven value");

            ResolvedRequestDto result = service.resolve(template, bindings, data);

            var multipartBody = (ResolvedMultipartBodyDto) result.getBody();
            assertThat(multipartBody.getParts().get(0).getResolvedValue()).isEqualTo("data-driven value");
        }
    }

    @Nested
    @DisplayName("URL-encoded body resolution")
    class UrlEncodedBodyResolution {

        @Test
        void shouldResolvePlaceholderInUrlEncodedValue() {
            when(templateVariableResolver.resolveVariable(eq("user_name"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("john_doe");

            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("username")
                                    .value("${{user_name}}")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            assertThat(result.getBody()).isInstanceOf(ResolvedUrlEncodedBodyDto.class);
            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries()).hasSize(1);
            assertThat(urlEncodedBody.getEntries().get(0).getKey()).isEqualTo("username");
            assertThat(urlEncodedBody.getEntries().get(0).getValue()).isEqualTo("john_doe");
        }

        @Test
        void shouldResolveMultipleUrlEncodedEntries() {
            when(templateVariableResolver.resolveVariable(eq("grant"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("authorization_code");
            when(templateVariableResolver.resolveVariable(eq("code_val"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("abc123");

            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(
                                    KeyValueTemplateDto.builder()
                                            .key("grant_type")
                                            .value("${{grant}}")
                                            .build(),
                                    KeyValueTemplateDto.builder()
                                            .key("code")
                                            .value("${{code_val}}")
                                            .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries()).hasSize(2);
            assertThat(urlEncodedBody.getEntries().get(0).getValue()).isEqualTo("authorization_code");
            assertThat(urlEncodedBody.getEntries().get(1).getValue()).isEqualTo("abc123");
        }

        @Test
        void shouldStringifyNonStringResolvedValueInUrlEncoded() {
            // URL-encoded values go through resolveString, which calls toString()
            when(templateVariableResolver.resolveVariable(eq("count"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(42);

            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("count")
                                    .value("${{count}}")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries().get(0).getValue()).isEqualTo("42");
        }

        @Test
        void shouldStringifyBooleanInUrlEncoded() {
            when(templateVariableResolver.resolveVariable(eq("enabled"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn(true);

            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("enabled")
                                    .value("${{enabled}}")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries().get(0).getValue()).isEqualTo("true");
        }

        @Test
        void shouldHandleNullContentInUrlEncodedBody() {
            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder().content(null).build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries()).isEmpty();
        }

        @Test
        void shouldPreserveKeyInUrlEncodedEntry() {
            when(templateVariableResolver.resolveVariable(eq("token"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("secret123");

            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("access_token")
                                    .value("${{token}}")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries().get(0).getKey()).isEqualTo("access_token");
        }

        @Test
        void shouldHandleNullValueInUrlEncodedEntry() {
            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("empty")
                                    .value(null)
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries()).hasSize(1);
            assertThat(urlEncodedBody.getEntries().get(0).getValue()).isNull();
        }

        @Test
        void shouldResolveEmbeddedPlaceholderInUrlEncodedValue() {
            when(templateVariableResolver.resolveVariable(eq("host"), isNull(), any(), anyMap(), anyList()))
                    .thenReturn("example.com");

            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("redirect_uri")
                                    .value("https://${{host}}/callback")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries().get(0).getValue()).isEqualTo("https://example.com/callback");
        }

        @Test
        void shouldResolveUrlEncodedWithBindingsFromData() {
            var binding = InputBindingDto.builder()
                    .templateVariable("client_id")
                    .dataField("clientIdCol")
                    .build();
            when(templateVariableResolver.resolveVariable(eq("client_id"), isNull(), eq(binding), anyMap(), anyList()))
                    .thenReturn("my-client-app");

            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("client_id")
                                    .value("${{client_id}}")
                                    .build()))
                            .build())
                    .build();
            var bindings = List.of(binding);
            var data = Map.<String, Object>of("clientIdCol", "my-client-app");

            ResolvedRequestDto result = service.resolve(template, bindings, data);

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries().get(0).getValue()).isEqualTo("my-client-app");
        }

        @Test
        void shouldResolveWithDefaultValueInUrlEncoded() {
            when(templateVariableResolver.resolveVariable(eq("scope"), eq("openid"), any(), anyMap(), anyList()))
                    .thenReturn("openid");

            var template = RequestTemplateDto.builder()
                    .body(UrlEncodedFormRequestBodyDto.builder()
                            .content(List.of(KeyValueTemplateDto.builder()
                                    .key("scope")
                                    .value("${{scope:openid}}")
                                    .build()))
                            .build())
                    .build();

            ResolvedRequestDto result = service.resolve(template, List.of(), Map.of());

            var urlEncodedBody = (ResolvedUrlEncodedBodyDto) result.getBody();
            assertThat(urlEncodedBody.getEntries().get(0).getValue()).isEqualTo("openid");
        }
    }
}
