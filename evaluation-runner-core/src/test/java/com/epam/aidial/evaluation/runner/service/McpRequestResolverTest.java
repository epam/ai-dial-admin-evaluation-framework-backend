package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpRequestResolverTest {

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    @InjectMocks
    private McpRequestResolver resolver;

    @Test
    @DisplayName("Resolves single variable reference preserving type")
    void resolveSingleVariable() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("query", "${{searchQuery}}"));

        Map<String, Object> data = Map.of("searchQuery", "hello world");
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        assertThat(result.getArguments()).containsEntry("query", "hello world");
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Preserves numeric type for single variable reference")
    void preserveNumericType() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("count", "${{num}}"));

        Map<String, Object> data = Map.of("num", 42);
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        assertThat(result.getArguments()).containsEntry("count", 42);
    }

    @Test
    @DisplayName("Passes through constant values")
    void constantPassthrough() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("mode", "strict", "count", 5));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getArguments()).containsEntry("mode", "strict");
        assertThat(result.getArguments()).containsEntry("count", 5);
    }

    @Test
    @DisplayName("Handles mixed text and variable references")
    void mixedTextAndVariable() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("query", "search for ${{term}} in ${{scope}}"));

        Map<String, Object> data = Map.of("term", "cats", "scope", "images");
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        assertThat(result.getArguments()).containsEntry("query", "search for cats in images");
    }

    @Test
    @DisplayName("Missing variable without default produces REQUIRED warning")
    void missingVariableProducesWarning() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("query", "${{missing}}"));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getArguments()).containsEntry("query", null);
        assertThat(result.getWarnings()).hasSize(1);
        ValidationWarningDto warning = result.getWarnings().get(0);
        assertThat(warning.getFieldName()).isEqualTo("missing");
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
    }

    @Test
    @DisplayName("Default value used when variable not found")
    void defaultValueUsed() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("limit", "${{max_results:10}}"));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getArguments()).containsEntry("limit", 10L);
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Default value not used when variable is found")
    void defaultValueNotUsedWhenPresent() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("limit", "${{max_results:10}}"));

        Map<String, Object> data = Map.of("max_results", 25);
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        assertThat(result.getArguments()).containsEntry("limit", 25);
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Boolean default value parsed correctly")
    void booleanDefaultValue() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("verbose", "${{flag:true}}"));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getArguments()).containsEntry("verbose", true);
    }

    @Test
    @DisplayName("String default value preserved")
    void stringDefaultValue() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("format", "${{fmt:json}}"));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getArguments()).containsEntry("format", "json");
    }

    @Test
    @DisplayName("Default value in mixed text")
    void defaultValueInMixedText() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("query", "search ${{term:cats}} limit ${{max:5}}"));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getArguments()).containsEntry("query", "search cats limit 5");
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Null argument template returns empty map")
    void nullTemplate() {
        McpRequestResolver.ResolutionResult result = resolver.resolve(null, null, Map.of());
        assertThat(result.getArguments()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Null test case data treated as empty")
    void nullData() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("key", "constant"));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, null);

        assertThat(result.getArguments()).containsEntry("key", "constant");
    }

    @Test
    @DisplayName("Resolves nested map values")
    void resolveNestedMap() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("field", "${{val}}");
        nested.put("fixed", "constant");

        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("config", nested));

        Map<String, Object> data = Map.of("val", "resolved");
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedConfig =
                (Map<String, Object>) result.getArguments().get("config");
        assertThat(resolvedConfig).containsEntry("field", "resolved");
        assertThat(resolvedConfig).containsEntry("fixed", "constant");
    }

    @Test
    @DisplayName("Resolves list values")
    void resolveListValues() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("items", List.of("${{first}}", "static", "${{second}}")));

        Map<String, Object> data = Map.of("first", "a", "second", "b");
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        assertThat(result.getArguments().get("items")).isEqualTo(List.of("a", "static", "b"));
    }

    @Test
    @DisplayName("Boolean values pass through")
    void booleanPassthrough() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("verbose", true));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getArguments()).containsEntry("verbose", true);
    }

    @Test
    @DisplayName("${{param|string}} full-value path resolves to data map value")
    void typeHintFullValueResolvesToData() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("input", "${{input_doc|string}}"));

        Map<String, Object> data = Map.of("input_doc", "file-content-bytes");
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        assertThat(result.getArguments()).containsEntry("input", "file-content-bytes");
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("${{param|array}} embedded path resolves correctly")
    void typeHintEmbeddedPathResolvesCorrectly() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("query", "process ${{doc|file}} now"));

        Map<String, Object> data = Map.of("doc", "document.pdf");
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        assertThat(result.getArguments()).containsEntry("query", "process document.pdf now");
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("${{param|string:default}} falls back to default when variable missing")
    void typeHintWithDefaultFallsBack() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("input", "${{doc|string:files/default.txt}}"));

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getArguments()).containsEntry("input", "files/default.txt");
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("${{param|array}} preserves typed value (array) in full-value path")
    void typeHintPreservesArrayType() {
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(Map.of("items", "${{data|array}}"));

        List<String> arrayValue = List.of("a", "b", "c");
        Map<String, Object> data = Map.of("data", arrayValue);
        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

        assertThat(result.getArguments()).containsEntry("items", arrayValue);
    }

    @Test
    @DisplayName("Multiple missing variables produce multiple warnings")
    void multipleMissingVariables() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("a", "${{x}}");
        args.put("b", "${{y}}");
        ArgumentTemplateDto template = new ArgumentTemplateDto();
        template.setArguments(args);

        McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

        assertThat(result.getWarnings()).hasSize(2);
        assertThat(result.getWarnings()).allMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED);
    }

    @Nested
    @DisplayName("Input binding resolution")
    class InputBindingTest {

        @Test
        @DisplayName("Binding with constantValue overrides data lookup")
        void bindingConstantValueOverridesData() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("query", "${{searchQuery}}"));

            List<InputBindingDto> bindings = List.of(InputBindingDto.builder()
                    .templateVariable("searchQuery")
                    .constantValue("fixed-value")
                    .build());

            Map<String, Object> data = Map.of("searchQuery", "from-data");
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, bindings, data);

            assertThat(result.getArguments()).containsEntry("query", "fixed-value");
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("Binding with dataField resolves from different data key")
        void bindingDataFieldResolvesFromDifferentKey() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("query", "${{searchQuery}}"));

            List<InputBindingDto> bindings = List.of(InputBindingDto.builder()
                    .templateVariable("searchQuery")
                    .dataField("smiles_string")
                    .build());

            Map<String, Object> data = Map.of("smiles_string", "CCO");
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, bindings, data);

            assertThat(result.getArguments()).containsEntry("query", "CCO");
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("Binding with dataField falls back to default when data key missing")
        void bindingDataFieldFallsBackToDefault() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("query", "${{searchQuery:fallback}}"));

            List<InputBindingDto> bindings = List.of(InputBindingDto.builder()
                    .templateVariable("searchQuery")
                    .dataField("missing_key")
                    .build());

            McpRequestResolver.ResolutionResult result = resolver.resolve(template, bindings, Map.of());

            assertThat(result.getArguments()).containsEntry("query", "fallback");
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("No binding falls back to direct variable name lookup")
        void noBindingFallsBackToDirectLookup() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("query", "${{searchQuery}}"));

            Map<String, Object> data = Map.of("searchQuery", "direct-value");
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, List.of(), data);

            assertThat(result.getArguments()).containsEntry("query", "direct-value");
        }

        @Test
        @DisplayName("Binding constantValue preserves non-string types")
        void bindingConstantValuePreservesType() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("count", "${{num}}"));

            List<InputBindingDto> bindings = List.of(InputBindingDto.builder()
                    .templateVariable("num")
                    .constantValue(42)
                    .build());

            McpRequestResolver.ResolutionResult result = resolver.resolve(template, bindings, Map.of());

            assertThat(result.getArguments()).containsEntry("count", 42);
        }

        @Test
        @DisplayName("Binding in mixed-text string uses string substitution")
        void bindingInMixedText() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("msg", "Hello ${{name}}, welcome!"));

            List<InputBindingDto> bindings = List.of(InputBindingDto.builder()
                    .templateVariable("name")
                    .constantValue("Alice")
                    .build());

            McpRequestResolver.ResolutionResult result = resolver.resolve(template, bindings, Map.of());

            assertThat(result.getArguments()).containsEntry("msg", "Hello Alice, welcome!");
        }
    }

    @Nested
    @DisplayName("|file type hint resolution")
    class FileTypeHintResolution {

        @Test
        @DisplayName("${{doc|file}} with string value resolves via DialFileRefResolver")
        void shouldResolveFileHintViaDialFileRefResolver() {
            when(dialFileRefResolver.resolveToDialRef("@ef/suites/abc/contract.pdf"))
                    .thenReturn("files/real-bucket/suites/abc/contract.pdf");

            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("document", "${{contract|file}}"));

            Map<String, Object> data = Map.of("contract", "@ef/suites/abc/contract.pdf");
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

            assertThat(result.getArguments()).containsEntry("document", "files/real-bucket/suites/abc/contract.pdf");
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("${{doc|file}} with null value produces REQUIRED warning (no file resolution)")
        void shouldProduceRequiredWarningForNullFileValue() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("document", "${{contract|file}}"));

            McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

            assertThat(result.getArguments()).containsEntry("document", null);
            assertThat(result.getWarnings()).hasSize(1);
            assertThat(result.getWarnings().get(0).getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
            verify(dialFileRefResolver, never()).resolveToDialRef(any());
        }

        @Test
        @DisplayName("${{doc|file}} with non-String value returns as-is")
        void shouldReturnAsIsForNonStringFileValue() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("doc", "${{doc|file}}"));

            Map<String, Object> data = Map.of("doc", 42);
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

            assertThat(result.getArguments()).containsEntry("doc", 42);
            verify(dialFileRefResolver, never()).resolveToDialRef(any());
        }

        @Test
        @DisplayName("${{doc}} without |file hint returns raw string")
        void shouldReturnRawStringWithoutFileHint() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("doc", "${{doc}}"));

            Map<String, Object> data = Map.of("doc", "@ef/suites/abc/data.csv");
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

            assertThat(result.getArguments()).containsEntry("doc", "@ef/suites/abc/data.csv");
            verify(dialFileRefResolver, never()).resolveToDialRef(any());
        }

        @Test
        @DisplayName("Embedded ${{doc|file}} does NOT trigger file resolution")
        void shouldNotResolveEmbeddedFileHint() {
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("path", "prefix/${{doc|file}}/suffix"));

            Map<String, Object> data = Map.of("doc", "@ef/suites/abc/data.csv");
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

            assertThat(result.getArguments()).containsEntry("path", "prefix/@ef/suites/abc/data.csv/suffix");
            verify(dialFileRefResolver, never()).resolveToDialRef(any());
        }

        @Test
        @DisplayName("${{doc|file:@ef/default.pdf}} with no binding resolves default via DialFileRefResolver")
        void shouldResolveDefaultValueAsFileRef() {
            when(dialFileRefResolver.resolveToDialRef("@ef/suites/abc/default.pdf"))
                    .thenReturn("files/real-bucket/suites/abc/default.pdf");

            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("document", "${{doc|file:@ef/suites/abc/default.pdf}}"));

            McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, Map.of());

            assertThat(result.getArguments()).containsEntry("document", "files/real-bucket/suites/abc/default.pdf");
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("${{doc|FILE}} uppercase hint resolves as file (case-insensitive)")
        void shouldResolveCaseInsensitiveFileHint() {
            when(dialFileRefResolver.resolveToDialRef("@ef/suites/abc/contract.pdf"))
                    .thenReturn("files/real-bucket/suites/abc/contract.pdf");

            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("document", "${{contract|FILE}}"));

            Map<String, Object> data = Map.of("contract", "@ef/suites/abc/contract.pdf");
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

            assertThat(result.getArguments()).containsEntry("document", "files/real-bucket/suites/abc/contract.pdf");
        }

        @Test
        @DisplayName("Multiple |file variables each resolved independently")
        void shouldResolveMultipleFileVariablesIndependently() {
            when(dialFileRefResolver.resolveToDialRef("@ef/suites/abc/first.pdf"))
                    .thenReturn("files/real-bucket/suites/abc/first.pdf");
            when(dialFileRefResolver.resolveToDialRef("@ef/suites/abc/second.pdf"))
                    .thenReturn("files/real-bucket/suites/abc/second.pdf");

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("doc1", "${{a|file}}");
            args.put("doc2", "${{b|file}}");
            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(args);

            Map<String, Object> data = Map.of(
                    "a", "@ef/suites/abc/first.pdf",
                    "b", "@ef/suites/abc/second.pdf");
            McpRequestResolver.ResolutionResult result = resolver.resolve(template, null, data);

            assertThat(result.getArguments())
                    .containsEntry("doc1", "files/real-bucket/suites/abc/first.pdf")
                    .containsEntry("doc2", "files/real-bucket/suites/abc/second.pdf");
        }

        @Test
        @DisplayName("|file with constantValue binding resolves via DialFileRefResolver")
        void shouldResolveConstantValueBinding() {
            when(dialFileRefResolver.resolveToDialRef("@ef/suites/abc/report.pdf"))
                    .thenReturn("files/real-bucket/suites/abc/report.pdf");

            ArgumentTemplateDto template = new ArgumentTemplateDto();
            template.setArguments(Map.of("attachment", "${{doc|file}}"));

            List<InputBindingDto> bindings = List.of(InputBindingDto.builder()
                    .templateVariable("doc")
                    .constantValue("@ef/suites/abc/report.pdf")
                    .build());

            McpRequestResolver.ResolutionResult result = resolver.resolve(template, bindings, Map.of());

            assertThat(result.getArguments()).containsEntry("attachment", "files/real-bucket/suites/abc/report.pdf");
        }
    }
}
