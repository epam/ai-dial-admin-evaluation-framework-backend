package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@DisplayName("JsonataSourcePreprocessor")
@ExtendWith(MockitoExtension.class)
class JsonataSourcePreprocessorTest {

    @Mock
    private TemplateVariableResolver templateVariableResolver;

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonataSourcePreprocessor preprocessor;

    private List<ValidationWarningDto> warnings;

    private void setUp() {
        preprocessor = new JsonataSourcePreprocessor(templateVariableResolver, dialFileRefResolver, objectMapper);
        warnings = new ArrayList<>();
    }

    @Nested
    @DisplayName("Quoted-full-value substitution")
    class QuotedFullValue {

        @Test
        @DisplayName("entire double-quoted literal placeholder serializes typed String value as JSON string literal")
        void fullValueDoubleQuotedString() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("question"), isNull(), any(), anyMap(), any()))
                    .thenReturn("What is AI?");

            String result = preprocessor.preprocess("{\"content\": \"${{question}}\"}", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("{\"content\": \"What is AI?\"}");
        }

        @Test
        @DisplayName("entire single-quoted literal placeholder serializes typed value, preserving quote style")
        void fullValueSingleQuotedString() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("name"), isNull(), any(), anyMap(), any()))
                    .thenReturn("Alice");

            String result = preprocessor.preprocess("greeting = '${{name}}'", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("greeting = \"Alice\"");
        }

        @Test
        @DisplayName("full-value placeholder resolving to a number produces a bare numeric JSONata literal")
        void fullValueNumber() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("temp"), isNull(), any(), anyMap(), any()))
                    .thenReturn(0.7);

            String result = preprocessor.preprocess("{\"temperature\": \"${{temp}}\"}", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("{\"temperature\": 0.7}");
        }

        @Test
        @DisplayName("full-value placeholder resolving to an array produces a JSONata array literal")
        void fullValueArray() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("items"), isNull(), any(), anyMap(), any()))
                    .thenReturn(List.of("a", "b"));

            String result = preprocessor.preprocess("\"${{items}}\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("[\"a\",\"b\"]");
        }

        @Test
        @DisplayName("|file type hint on a full-value placeholder resolves through DialFileRefResolver")
        void fullValueFileHint() {
            setUp();
            String shortRef = "@ef/suites/abc/file.bin";
            String dialRef = "files/real-bucket/suites/abc/file.bin";
            when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), any(), anyMap(), any()))
                    .thenReturn(shortRef);
            when(dialFileRefResolver.resolveToDialRef(shortRef)).thenReturn(dialRef);

            String result = preprocessor.preprocess("\"${{doc|file}}\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("\"" + dialRef + "\"");
        }

        @Test
        @DisplayName("unresolved full-value placeholder serializes as JSON null literal")
        void fullValueUnresolved() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("missing"), isNull(), any(), anyMap(), any()))
                    .thenReturn(null);

            String result = preprocessor.preprocess("\"${{missing}}\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("null");
        }

        @Test
        @DisplayName("full-value placeholder resolving to a Map with a null entry preserves the explicit null")
        void fullValueMapWithNullEntry() {
            setUp();
            Map<String, Object> withNull = new HashMap<>();
            withNull.put("key", null);
            when(templateVariableResolver.resolveVariable(eq("obj"), isNull(), any(), anyMap(), any()))
                    .thenReturn(withNull);

            String result = preprocessor.preprocess("\"${{obj}}\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("{\"key\":null}");
        }
    }

    @Nested
    @DisplayName("Bare substitution (outside any string literal)")
    class Bare {

        @Test
        @DisplayName("bare placeholder inside a function call is replaced with a JSON array literal")
        void barePlaceholderInFunctionCall() {
            setUp();
            Map<String, Object> newMessage = new LinkedHashMap<>();
            newMessage.put("role", "user");
            newMessage.put("content", "hi");
            when(templateVariableResolver.resolveVariable(eq("messages"), isNull(), any(), anyMap(), any()))
                    .thenReturn(List.of(newMessage));

            String result = preprocessor.preprocess("$append($history, ${{messages}})", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("$append($history, [{\"role\":\"user\",\"content\":\"hi\"}])");
        }

        @Test
        @DisplayName("bare unresolved placeholder serializes as JSON null literal")
        void bareUnresolved() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("missing"), isNull(), any(), anyMap(), any()))
                    .thenReturn(null);

            String result = preprocessor.preprocess("$exists(${{missing}})", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("$exists(null)");
        }
    }

    @Nested
    @DisplayName("Embedded-in-literal substitution")
    class Embedded {

        @Test
        @DisplayName("placeholder embedded with surrounding text is stringified in place")
        void embeddedWithSurroundingText() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("name"), isNull(), any(), anyMap(), any()))
                    .thenReturn("Alice");

            String result = preprocessor.preprocess("\"Hello ${{name}}!\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("\"Hello Alice!\"");
        }

        @Test
        @DisplayName("unresolved embedded placeholder substitutes an empty string (mirrors resolveString)")
        void embeddedUnresolved() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("missing"), isNull(), any(), anyMap(), any()))
                    .thenReturn(null);

            String result = preprocessor.preprocess("\"Value: [${{missing}}]\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("\"Value: []\"");
        }

        @Test
        @DisplayName("embedded value containing the active double-quote char is escaped")
        void embeddedValueContainingDoubleQuote() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("quoted"), isNull(), any(), anyMap(), any()))
                    .thenReturn("say \"hi\"");

            String result = preprocessor.preprocess("\"text: ${{quoted}}\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("\"text: say \\\"hi\\\"\"");
        }

        @Test
        @DisplayName("embedded value containing a backslash is escaped")
        void embeddedValueContainingBackslash() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("path"), isNull(), any(), anyMap(), any()))
                    .thenReturn("C:\\temp");

            String result = preprocessor.preprocess("\"path: ${{path}}\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("\"path: C:\\\\temp\"");
        }

        @Test
        @DisplayName("embedded value containing a newline is escaped")
        void embeddedValueContainingNewline() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("multiline"), isNull(), any(), anyMap(), any()))
                    .thenReturn("line1\nline2");

            String result = preprocessor.preprocess("\"text: ${{multiline}}\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("\"text: line1\\nline2\"");
        }

        @Test
        @DisplayName(
                "embedded value that itself looks like a placeholder is escaped as literal text, not re-substituted")
        void embeddedValueLooksLikePlaceholder() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("evil"), isNull(), any(), anyMap(), any()))
                    .thenReturn("${{other}}");

            String result = preprocessor.preprocess("\"text: ${{evil}}\"", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("\"text: ${{other}}\"");
            // The substituted text is not re-scanned for placeholders — a second variable lookup never happens.
        }

        @Test
        @DisplayName("embedded placeholder inside a single-quoted literal is escaped for that quote context")
        void embeddedInSingleQuotedLiteral() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("name"), isNull(), any(), anyMap(), any()))
                    .thenReturn("it's ok");

            String result = preprocessor.preprocess("'value: ${{name}}'", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("'value: it\\'s ok'");
        }
    }

    @Nested
    @DisplayName("Passthrough behavior")
    class Passthrough {

        @Test
        @DisplayName("a literal with no placeholder is copied unchanged, escapes included")
        void literalWithoutPlaceholderUnchanged() {
            setUp();
            String source = "\"already \\\"escaped\\\" text\"";

            String result = preprocessor.preprocess(source, Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo(source);
        }

        @Test
        @DisplayName("bare JSONata code without any placeholder is unaffected")
        void codeWithoutPlaceholderUnaffected() {
            setUp();
            String source = "$sum(scores) / $count(scores)";

            String result = preprocessor.preprocess(source, Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo(source);
        }

        @Test
        @DisplayName("backtick-quoted field names are passed through as plain code")
        void backtickFieldNamePassthrough() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("x"), isNull(), any(), anyMap(), any()))
                    .thenReturn(5);

            String result = preprocessor.preprocess("`field name`.value + ${{x}}", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("`field name`.value + 5");
        }

        @Test
        @DisplayName("null source is returned unchanged")
        void nullSourceReturnedUnchanged() {
            setUp();
            String result = preprocessor.preprocess(null, Map.of(), Map.of(), warnings);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("empty source is returned unchanged")
        void emptySourceReturnedUnchanged() {
            setUp();
            String result = preprocessor.preprocess("", Map.of(), Map.of(), warnings);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Block comment handling")
    class BlockComments {

        @Test
        @DisplayName("a placeholder after a comment containing an apostrophe is still substituted")
        void placeholderAfterCommentWithApostrophe() {
            setUp();
            when(templateVariableResolver.resolveVariable(eq("x"), isNull(), any(), anyMap(), any()))
                    .thenReturn(5);

            String result = preprocessor.preprocess("/* it's a comment */ ${{x}}", Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo("/* it's a comment */ 5");
        }

        @Test
        @DisplayName(
                "a placeholder-shaped span inside a comment is left alone (comment content passed through verbatim)")
        void placeholderInsideCommentIsNotSubstituted() {
            setUp();

            String source = "/* example: ${{x}} */ 1 + 1";
            String result = preprocessor.preprocess(source, Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo(source);
        }

        @Test
        @DisplayName("an unterminated comment does not throw and copies the remainder verbatim")
        void unterminatedCommentDoesNotThrow() {
            setUp();

            String source = "1 + 1 /* unterminated ${{x}}";
            String result = preprocessor.preprocess(source, Map.of(), Map.of(), warnings);

            assertThat(result).isEqualTo(source);
        }
    }

    @Nested
    @DisplayName("neutralize(String) — placeholder-neutral write-time validation shape")
    class Neutralize {

        @Test
        @DisplayName("bare placeholder in value position neutralizes to JSON null")
        void barePlaceholderNeutralizesToNull() {
            setUp();

            String result = preprocessor.neutralize("{\"q\": ${{question}}}");

            assertThat(result).isEqualTo("{\"q\": null}");
        }

        @Test
        @DisplayName("bare placeholder as a function argument neutralizes to JSON null")
        void barePlaceholderAsFunctionArgumentNeutralizesToNull() {
            setUp();

            String result = preprocessor.neutralize("$append($history, ${{messages}})");

            assertThat(result).isEqualTo("$append($history, null)");
        }

        @Test
        @DisplayName("quoted full-value placeholder neutralizes to JSON null")
        void quotedFullValuePlaceholderNeutralizesToNull() {
            setUp();

            String result = preprocessor.neutralize("{\"q\": \"${{question}}\"}");

            assertThat(result).isEqualTo("{\"q\": null}");
        }

        @Test
        @DisplayName("embedded-in-literal placeholder neutralizes to an empty string, leaving surrounding text intact")
        void embeddedPlaceholderNeutralizesToEmptyString() {
            setUp();

            String result = preprocessor.neutralize("\"Hello ${{name}}!\"");

            assertThat(result).isEqualTo("\"Hello !\"");
        }

        @Test
        @DisplayName("neutralize never invokes the template variable resolver or the DIAL file resolver")
        void neutralizeNeverTouchesResolvers() {
            setUp();

            preprocessor.neutralize("{\"q\": ${{question}}, \"d\": \"${{doc|file}}\"}");

            org.mockito.Mockito.verifyNoInteractions(templateVariableResolver, dialFileRefResolver);
        }
    }

    @Nested
    @DisplayName("Binding-driven resolution")
    class BindingDriven {

        @Test
        @DisplayName("a binding with a data field resolves from test case data")
        void resolvesFromDataFieldBinding() {
            setUp();
            InputBindingDto binding = InputBindingDto.builder()
                    .templateVariable("question")
                    .dataField("questionCol")
                    .build();
            when(templateVariableResolver.resolveVariable(eq("question"), isNull(), eq(binding), anyMap(), any()))
                    .thenReturn("bound value");

            String result = preprocessor.preprocess(
                    "\"${{question}}\"", Map.of("question", binding), Map.of("questionCol", "bound value"), warnings);

            assertThat(result).isEqualTo("\"bound value\"");
        }
    }
}
