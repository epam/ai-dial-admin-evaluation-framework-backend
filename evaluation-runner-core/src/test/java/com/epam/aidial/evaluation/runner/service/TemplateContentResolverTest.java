package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolverStubs;
import com.epam.aidial.evaluation.runner.client.dialcore.SkillRefResolver;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
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
 * {@link RequestResolver} (see WP2 task 2 of the jsonata-request-templates change) — these
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
        DialFileRefResolverStubs.withRealFileDetection(dialFileRefResolver);
        resolver = new TemplateContentResolver(
                templateVariableResolver,
                new ResourceRefPlaceholderResolver(
                        dialFileRefResolver, new SkillRefResolver(new BindingTypeResolver())));
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

        Object result = resolver.resolveObject("${{temperature}}", Map.of(), Map.of(), warnings, null);

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

        Object result = resolver.resolveObject("${{doc|file}}", Map.of(), Map.of(), warnings, null);

        assertThat(result).isEqualTo(dialRef);
    }

    private static final List<FieldDefinitionDto> SCHEMA = List.of(
            FieldDefinitionDto.builder()
                    .name("attachment")
                    .type(SchemaFieldType.FILE)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("title")
                    .type(SchemaFieldType.STRING)
                    .build());

    private static InputBindingDto binding(String variable, String dataField) {
        return InputBindingDto.builder()
                .templateVariable(variable)
                .dataField(dataField)
                .build();
    }

    @Test
    @DisplayName("resolveObject resolves a hintless full-value placeholder bound to a FILE column to a DIAL ref")
    void resolveObjectResolvesHintlessFileColumnToDialRef() {
        String shortRef = "@ef/datasets/abc/doc.pdf";
        String dialRef = "files/real-bucket/datasets/abc/doc.pdf";
        InputBindingDto binding = binding("doc", "attachment");
        when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn(shortRef);
        when(dialFileRefResolver.resolveToDialRef(shortRef)).thenReturn(dialRef);

        Object result = resolver.resolveObject("${{doc}}", Map.of("doc", binding), Map.of(), warnings, SCHEMA);

        assertThat(result).isEqualTo(dialRef);
    }

    @Test
    @DisplayName("resolveObject resolves a hintless FILE-bound placeholder nested in a Map and a List")
    void resolveObjectResolvesHintlessFileColumnWhenNested() {
        String shortRef = "@ef/datasets/abc/doc.pdf";
        String dialRef = "files/real-bucket/datasets/abc/doc.pdf";
        InputBindingDto binding = binding("doc", "attachment");
        when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn(shortRef);
        when(dialFileRefResolver.resolveToDialRef(shortRef)).thenReturn(dialRef);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resolver.resolveObject(
                Map.of("outer", Map.of("key", "${{doc}}"), "list", List.of("${{doc}}")),
                Map.of("doc", binding),
                Map.of(),
                warnings,
                SCHEMA);

        @SuppressWarnings("unchecked")
        Map<String, Object> outer = (Map<String, Object>) result.get("outer");
        assertThat(outer).containsEntry("key", dialRef);
        assertThat(result.get("list")).isEqualTo(List.of(dialRef));
    }

    @Test
    @DisplayName("resolveObject leaves a hintless placeholder bound to a STRING column unchanged")
    void resolveObjectLeavesHintlessStringColumnUnchanged() {
        InputBindingDto binding = binding("t", "title");
        when(templateVariableResolver.resolveVariable(eq("t"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn("@ef/datasets/abc/doc.pdf");

        Object result = resolver.resolveObject("${{t}}", Map.of("t", binding), Map.of(), warnings, SCHEMA);

        assertThat(result).isEqualTo("@ef/datasets/abc/doc.pdf");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("resolveObject does not convert a FILE-bound placeholder embedded in surrounding text")
    void resolveObjectDoesNotConvertEmbeddedFilePlaceholder() {
        InputBindingDto binding = binding("doc", "attachment");
        when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn("@ef/datasets/abc/doc.pdf");

        Object result = resolver.resolveObject("see ${{doc}}", Map.of("doc", binding), Map.of(), warnings, SCHEMA);

        assertThat(result).isEqualTo("see @ef/datasets/abc/doc.pdf");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("resolveObject keeps the raw value for |string on a FILE column (explicit hint wins)")
    void resolveObjectExplicitStringHintWinsOverFileColumn() {
        InputBindingDto binding = binding("doc", "attachment");
        when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn("@ef/datasets/abc/doc.pdf");

        Object result = resolver.resolveObject("${{doc|string}}", Map.of("doc", binding), Map.of(), warnings, SCHEMA);

        assertThat(result).isEqualTo("@ef/datasets/abc/doc.pdf");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("resolveObject with a null schema converts only an explicit |file hint")
    void resolveObjectNullSchemaIgnoresFileColumn() {
        InputBindingDto binding = binding("doc", "attachment");
        when(templateVariableResolver.resolveVariable(eq("doc"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn("@ef/datasets/abc/doc.pdf");

        Object result = resolver.resolveObject("${{doc}}", Map.of("doc", binding), Map.of(), warnings, null);

        assertThat(result).isEqualTo("@ef/datasets/abc/doc.pdf");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
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
                warnings,
                null);

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
        assertThat(resolver.resolveObject(42, Map.of(), Map.of(), warnings, null))
                .isEqualTo(42);
        assertThat(resolver.resolveObject(true, Map.of(), Map.of(), warnings, null))
                .isEqualTo(true);
        assertThat(resolver.resolveObject(null, Map.of(), Map.of(), warnings, null))
                .isNull();
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

    private static final List<FieldDefinitionDto> SKILL_SCHEMA = List.of(FieldDefinitionDto.builder()
            .name("skill")
            .type(SchemaFieldType.SKILL)
            .build());

    private void stubSkillValue(String variable, Object value) {
        when(templateVariableResolver.resolveVariable(eq(variable), isNull(), any(), anyMap(), any()))
                .thenReturn(value);
    }

    @Test
    @DisplayName("resolveObject canonicalises a |skill full-value placeholder without touching DialFileRefResolver")
    void resolveObjectCanonicalisesSkillHint() {
        stubSkillValue("s", "skills/b/summariser/");

        Object result = resolver.resolveObject("${{s|skill}}", Map.of(), Map.of(), warnings, null);

        assertThat(result).isEqualTo("skills/b/summariser");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("resolveObject treats the skill hint case-insensitively")
    void resolveObjectSkillHintIsCaseInsensitive() {
        stubSkillValue("s", "skills/b/x/");

        Object result = resolver.resolveObject("${{s|SKILL}}", Map.of(), Map.of(), warnings, null);

        assertThat(result).isEqualTo("skills/b/x");
    }

    @Test
    @DisplayName("resolveObject canonicalises a hintless placeholder bound to a SKILL column")
    void resolveObjectCanonicalisesHintlessSkillColumn() {
        InputBindingDto binding = binding("s", "skill");
        when(templateVariableResolver.resolveVariable(eq("s"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn(" skills/b/x ");

        Object result = resolver.resolveObject("${{s}}", Map.of("s", binding), Map.of(), warnings, SKILL_SCHEMA);

        assertThat(result).isEqualTo("skills/b/x");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("resolveObject keeps the raw value for |string on a SKILL column (explicit hint wins)")
    void resolveObjectExplicitStringHintWinsOverSkillColumn() {
        InputBindingDto binding = binding("s", "skill");
        when(templateVariableResolver.resolveVariable(eq("s"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn(" skills/b/x ");

        Object result = resolver.resolveObject("${{s|string}}", Map.of("s", binding), Map.of(), warnings, SKILL_SCHEMA);

        assertThat(result).isEqualTo(" skills/b/x ");
    }

    @Test
    @DisplayName("resolveObject canonicalises an explicit |skill hint on a STRING column without a schema change")
    void resolveObjectExplicitSkillHintOnStringColumn() {
        InputBindingDto binding = binding("s", "text");
        List<FieldDefinitionDto> stringSchema = List.of(FieldDefinitionDto.builder()
                .name("text")
                .type(SchemaFieldType.STRING)
                .build());
        when(templateVariableResolver.resolveVariable(eq("s"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn(" skills/b/x ");

        Object result = resolver.resolveObject("${{s|skill}}", Map.of("s", binding), Map.of(), warnings, stringSchema);

        assertThat(result).isEqualTo("skills/b/x");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("resolveObject does not canonicalise a |skill placeholder embedded in surrounding text")
    void resolveObjectDoesNotCanonicaliseEmbeddedSkill() {
        stubSkillValue("s", "skills/b/x/");

        Object result = resolver.resolveObject("use ${{s|skill}} now", Map.of(), Map.of(), warnings, null);

        assertThat(result).isEqualTo("use skills/b/x/ now");
    }

    @Test
    @DisplayName("resolveObject passes a non-String |skill value through unchanged")
    void resolveObjectSkillNonStringUnchanged() {
        stubSkillValue("s", 5);

        Object result = resolver.resolveObject("${{s|skill}}", Map.of(), Map.of(), warnings, null);

        assertThat(result).isEqualTo(5);
    }

    @Test
    @DisplayName("resolveObject passes a malformed |skill value through unchanged")
    void resolveObjectSkillMalformedUnchanged() {
        stubSkillValue("s", "not-a-skill");

        Object result = resolver.resolveObject("${{s|skill}}", Map.of(), Map.of(), warnings, null);

        assertThat(result).isEqualTo("not-a-skill");
    }

    @Test
    @DisplayName("resolveObject canonicalises a skill nested as custom_content.skills[{url}] in a message")
    void resolveObjectCanonicalisesSkillNestedInMessageCustomContent() {
        InputBindingDto binding = binding("skill", "skill");
        when(templateVariableResolver.resolveVariable(eq("skill"), isNull(), eq(binding), anyMap(), any()))
                .thenReturn("skills/public/vc-test/");
        Map<String, Object> body = Map.of(
                "messages",
                List.of(Map.of(
                        "role",
                        "user",
                        "content",
                        "hi",
                        "custom_content",
                        Map.of("skills", List.of(Map.of("url", "${{skill}}"))))));

        Object result = resolver.resolveObject(body, Map.of("skill", binding), Map.of(), warnings, SKILL_SCHEMA);

        assertThat(result)
                .isEqualTo(Map.of(
                        "messages",
                        List.of(Map.of(
                                "role",
                                "user",
                                "content",
                                "hi",
                                "custom_content",
                                Map.of("skills", List.of(Map.of("url", "skills/public/vc-test")))))));
    }
}
