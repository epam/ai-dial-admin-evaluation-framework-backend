package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.exception.RequestBodyEvaluationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

@DisplayName("RequestBodyEvaluator")
@ExtendWith(MockitoExtension.class)
class RequestBodyEvaluatorTest {

    @Mock
    private TemplateVariableResolver templateVariableResolver;

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    private RequestBodyEvaluator evaluator;

    private List<ValidationWarningDto> warnings;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonataProperties jsonataProperties = new JsonataProperties();
        jsonataProperties.setEvaluationTimeoutMs(5000L);
        jsonataProperties.setMaxRecursionDepth(500);
        JsonataEvaluationService jsonataEvaluationService =
                new DashjoinJsonataEvaluationService(objectMapper, jsonataProperties);
        TemplateContentResolver templateContentResolver =
                new TemplateContentResolver(templateVariableResolver, dialFileRefResolver);
        JsonataSourcePreprocessor jsonataSourcePreprocessor =
                new JsonataSourcePreprocessor(templateVariableResolver, dialFileRefResolver, objectMapper);
        evaluator = new RequestBodyEvaluator(
                templateContentResolver, jsonataSourcePreprocessor, jsonataEvaluationService, objectMapper);
        warnings = new ArrayList<>();
    }

    @Nested
    @DisplayName("Map (legacy) content")
    class MapContent {

        @Test
        @DisplayName("a chat-completions-shaped Map body echoes structurally, including the F1 numeric caveat")
        void chatCompletionsBodyEchoesStructurally() {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("model", "gpt-4");
            content.put("temperature", 0.7);
            content.put("stream", false);
            content.put("max_tokens", 1024);
            content.put("explicit_double_one", 1.0);
            content.put("user", null);

            Map<String, Object> result = evaluator.evaluate(content, Map.of(), Map.of(), Map.of(), warnings);

            assertThat(result.get("model")).isEqualTo("gpt-4");
            assertThat(result.get("temperature")).isEqualTo(0.7);
            assertThat(result.get("stream")).isEqualTo(false);
            assertThat(result.get("max_tokens")).isEqualTo(1024);
            // F1 caveat (documented, not solved): an explicit double literal with no fractional part
            // loses its "double-ness" through dashjoin JSONata evaluation and echoes back as an integer.
            assertThat(result.get("explicit_double_one")).isEqualTo(1);
            assertThat(result).containsKey("user");
            assertThat(result.get("user")).isNull();
        }

        @Test
        @DisplayName("a placeholder inside a Map body resolves before evaluation, same as the legacy structural path")
        void placeholderInMapBodyResolves() {
            when(templateVariableResolver.resolveVariable(eq("prompt"), isNull(), any(), anyMap(), any()))
                    .thenReturn("Hello world");
            Map<String, Object> content = Map.of("prompt", "${{prompt}}");

            Map<String, Object> result = evaluator.evaluate(content, Map.of(), Map.of(), Map.of(), warnings);

            assertThat(result).containsEntry("prompt", "Hello world");
        }

        @Test
        @DisplayName("null content returns null (no body)")
        void nullContentReturnsNull() {
            Map<String, Object> result = evaluator.evaluate(null, Map.of(), Map.of(), Map.of(), warnings);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("String (JSONata source) content")
    class StringContent {

        @Test
        @DisplayName("turn-0 (unbound $history): $append yields just the new array, no null-prepend")
        void unboundHistoryYieldsUndefinedAppendSemantics() {
            String source = "{\"messages\": $append($history, [1])}";

            Map<String, Object> result = evaluator.evaluate(source, Map.of(), Map.of(), Map.of(), warnings);

            assertThat(result.get("messages")).isEqualTo(List.of(1));
        }

        @Test
        @DisplayName("bound $history: $append prepends the frame-bound history")
        void boundHistoryAppends() {
            String source = "{\"messages\": $append($history, [1])}";
            Map<String, Object> frameBindings = Map.of("history", List.of(0));

            Map<String, Object> result = evaluator.evaluate(source, Map.of(), Map.of(), frameBindings, warnings);

            assertThat(result.get("messages")).isEqualTo(List.of(0, 1));
        }

        @Test
        @DisplayName("a full-value placeholder resolving to an array is spliced as a JSONata array literal")
        void fullValueArrayInjectionIntoAppend() {
            Map<String, Object> newMessage = new LinkedHashMap<>();
            newMessage.put("role", "user");
            newMessage.put("content", "hi");
            when(templateVariableResolver.resolveVariable(eq("newMessages"), isNull(), any(), anyMap(), any()))
                    .thenReturn(List.of(newMessage));
            String source = "{\"messages\": $append($history, ${{newMessages}})}";

            Map<String, Object> result = evaluator.evaluate(source, Map.of(), Map.of(), Map.of(), warnings);

            assertThat(result.get("messages")).isEqualTo(List.of(newMessage));
        }

        @Test
        @DisplayName("embedded placeholder values with quotes/backslashes/newlines evaluate to the literal string")
        void embeddedAdversarialValuesEvaluateCorrectly() {
            when(templateVariableResolver.resolveVariable(eq("text"), isNull(), any(), anyMap(), any()))
                    .thenReturn("she said \"hi\" \\ then\nleft");
            String source = "{\"content\": \"prefix: ${{text}}\"}";

            Map<String, Object> result = evaluator.evaluate(source, Map.of(), Map.of(), Map.of(), warnings);

            assertThat(result.get("content")).isEqualTo("prefix: she said \"hi\" \\ then\nleft");
        }

        @Test
        @DisplayName("an embedded value that itself looks like a placeholder is not re-substituted")
        void embeddedValueLookingLikePlaceholderIsNotReSubstituted() {
            when(templateVariableResolver.resolveVariable(eq("evil"), isNull(), any(), anyMap(), any()))
                    .thenReturn("${{other}}");
            String source = "{\"content\": \"value: ${{evil}}\"}";

            Map<String, Object> result = evaluator.evaluate(source, Map.of(), Map.of(), Map.of(), warnings);

            assertThat(result.get("content")).isEqualTo("value: ${{other}}");
        }

        @Test
        @DisplayName(
                "frame binding with an explicit Java null value binds a real JSONata null, distinguishable from unbound")
        void frameBindingWithNullValueBindsExplicitNull() {
            String source = "{\"boundExists\": $exists($x), \"unboundExists\": $exists($y)}";
            Map<String, Object> frameBindings = new HashMap<>();
            frameBindings.put("x", null);

            Map<String, Object> result = evaluator.evaluate(source, Map.of(), Map.of(), frameBindings, warnings);

            assertThat(result.get("boundExists")).isEqualTo(true);
            assertThat(result.get("unboundExists")).isEqualTo(false);
        }

        @Test
        @DisplayName("invalid JSONata syntax is wrapped in RequestBodyEvaluationException")
        void invalidJsonataSyntaxThrows() {
            String source = "{\"a\": (unclosed}";

            assertThatThrownBy(() -> evaluator.evaluate(source, Map.of(), Map.of(), Map.of(), warnings))
                    .isInstanceOf(RequestBodyEvaluationException.class);
        }
    }

    @Nested
    @DisplayName("Runtime contract: result must be a JSON object")
    class ObjectResultContract {

        @Test
        @DisplayName("a non-object (array) evaluation result throws RequestBodyEvaluationException")
        void arrayResultThrows() {
            String source = "[1, 2, 3]";

            assertThatThrownBy(() -> evaluator.evaluate(source, Map.of(), Map.of(), Map.of(), warnings))
                    .isInstanceOf(RequestBodyEvaluationException.class)
                    .hasMessageContaining("JSON object");
        }

        @Test
        @DisplayName("a non-object (numeric) evaluation result throws RequestBodyEvaluationException")
        void numericResultThrows() {
            String source = "42";

            assertThatThrownBy(() -> evaluator.evaluate(source, Map.of(), Map.of(), Map.of(), warnings))
                    .isInstanceOf(RequestBodyEvaluationException.class);
        }
    }

    @Nested
    @DisplayName("Unsupported content type")
    class UnsupportedContentType {

        @Test
        @DisplayName("a List content (neither Map nor String) throws RequestBodyEvaluationException")
        void listContentThrows() {
            List<Object> content = List.of("not", "supported");

            assertThatThrownBy(() -> evaluator.evaluate(content, Map.of(), Map.of(), Map.of(), warnings))
                    .isInstanceOf(RequestBodyEvaluationException.class);
        }
    }
}
