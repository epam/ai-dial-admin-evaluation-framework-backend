package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the extracted placeholder-resolution machinery. This is a pure refactor out of
 * {@link ResolvedRequestService} (see WP2 task 2 of the jsonata-request-templates change) — these
 * tests pin the same resolveString/resolveObject behavior the extraction preserved.
 */
@DisplayName("TemplateContentResolver")
@ExtendWith(MockitoExtension.class)
class TemplateContentResolverTest {

    @Mock
    private TemplateVariableResolver templateVariableResolver;

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    private TemplateContentResolver resolver;

    private List<ValidationWarningDto> warnings;

    @BeforeEach
    void setUp() {
        resolver = new TemplateContentResolver(templateVariableResolver, dialFileRefResolver);
        warnings = new ArrayList<>();
    }

    @Test
    @DisplayName("resolveString substitutes an embedded placeholder by stringifying the resolved value")
    void resolveStringSubstitutesEmbeddedPlaceholder() {
        when(templateVariableResolver.resolveVariable(eq("name"), isNull(), any(), anyMap(), any()))
                .thenReturn("Alice");

        String result = resolver.resolveString("Hello ${{name}}!", Map.of(), Map.of(), warnings);

        assertThat(result).isEqualTo("Hello Alice!");
    }

    @Test
    @DisplayName("resolveString substitutes an unresolved placeholder with an empty string")
    void resolveStringSubstitutesUnresolvedAsEmpty() {
        when(templateVariableResolver.resolveVariable(eq("missing"), isNull(), any(), anyMap(), any()))
                .thenReturn(null);

        String result = resolver.resolveString("Value: [${{missing}}]", Map.of(), Map.of(), warnings);

        assertThat(result).isEqualTo("Value: []");
    }

    @Test
    @DisplayName("resolveObject preserves the resolved value's type for a full-value placeholder")
    void resolveObjectPreservesTypeForFullValuePlaceholder() {
        when(templateVariableResolver.resolveVariable(eq("temperature"), isNull(), any(), anyMap(), any()))
                .thenReturn(0.7);

        Object result = resolver.resolveObject("${{temperature}}", Map.of(), Map.of(), warnings);

        assertThat(result).isEqualTo(0.7).isInstanceOf(Double.class);
    }

    @Test
    @DisplayName("resolveObject resolves a |file type-hinted full-value placeholder to a DIAL ref")
    void resolveObjectResolvesFileHintToDialRef() {
        String shortRef = "@ef/suites/abc/file.bin";
        String dialRef = "files/real-bucket/suites/abc/file.bin";
        when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), any(), anyMap(), any()))
                .thenReturn(shortRef);
        when(dialFileRefResolver.resolveToDialRef(shortRef)).thenReturn(dialRef);

        Object result = resolver.resolveObject("${{doc|file}}", Map.of(), Map.of(), warnings);

        assertThat(result).isEqualTo(dialRef);
    }

    @Test
    @DisplayName("resolveObject recurses into nested Maps and Lists")
    void resolveObjectRecursesIntoNestedStructures() {
        when(templateVariableResolver.resolveVariable(eq("inner"), isNull(), any(), anyMap(), any()))
                .thenReturn("resolved");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resolver.resolveObject(
                Map.of("outer", Map.of("inner", "${{inner}}"), "list", List.of("${{inner}}", "literal")),
                Map.of(),
                Map.of(),
                warnings);

        @SuppressWarnings("unchecked")
        Map<String, Object> outer = (Map<String, Object>) result.get("outer");
        assertThat(outer).containsEntry("inner", "resolved");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result.get("list");
        assertThat(list).containsExactly("resolved", "literal");
    }

    @Test
    @DisplayName("resolveObject passes non-placeholder scalars through unchanged")
    void resolveObjectPassesScalarsThrough() {
        assertThat(resolver.resolveObject(42, Map.of(), Map.of(), warnings)).isEqualTo(42);
        assertThat(resolver.resolveObject(true, Map.of(), Map.of(), warnings)).isEqualTo(true);
        assertThat(resolver.resolveObject(null, Map.of(), Map.of(), warnings)).isNull();
    }

    @Test
    @DisplayName("resolveVariable is called with the matching binding when bindingByVar has an entry")
    void resolveUsesMatchingBinding() {
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("q")
                .dataField("qCol")
                .build();
        when(templateVariableResolver.resolveVariable(eq("q"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn("bound value");

        String result = resolver.resolveString("${{q}}", Map.of("q", binding), Map.of("qCol", "bound value"), warnings);

        assertThat(result).isEqualTo("bound value");
    }

    @Test
    @DisplayName("serializeJsonPreservingNulls preserves an explicit null map entry that the project's "
            + "NON_NULL-configured ObjectMapper would otherwise silently drop")
    void serializeJsonPreservingNullsPreservesExplicitNulls() {
        JsonMapper nonNullMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("prompt", "hello");
        withNull.put("user", null);

        // Documents the caveat: the shared project ObjectMapper's global NON_NULL inclusion silently
        // drops the null-valued entry via plain POJO serialization.
        String naive = nonNullMapper.writeValueAsString(withNull);
        assertThat(naive).doesNotContain("\"user\"");

        // The null-preserving helper builds the JsonNode tree by hand instead, so the explicit null
        // survives regardless of the ObjectMapper's own inclusion configuration.
        String preserved = TemplateContentResolver.serializeJsonPreservingNulls(nonNullMapper, withNull);
        assertThat(preserved).contains("\"user\":null");
        assertThat(preserved).contains("\"prompt\":\"hello\"");
    }
}
