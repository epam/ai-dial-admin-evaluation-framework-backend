package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolverStubs;
import com.epam.aidial.evaluation.runner.client.dialcore.SkillRefResolver;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ResourceRefPlaceholderResolver")
@ExtendWith(MockitoExtension.class)
class ResourceRefPlaceholderResolverTest {

    private static final List<FieldDefinitionDto> SCHEMA = List.of(
            FieldDefinitionDto.builder()
                    .name("attachment")
                    .type(SchemaFieldType.FILE)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("skill")
                    .type(SchemaFieldType.SKILL)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("title")
                    .type(SchemaFieldType.STRING)
                    .build());

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    private ResourceRefPlaceholderResolver resolver;

    @BeforeEach
    void setUp() {
        DialFileRefResolverStubs.withRealFileDetection(dialFileRefResolver);
        resolver = new ResourceRefPlaceholderResolver(
                dialFileRefResolver, new SkillRefResolver(new BindingTypeResolver()));
    }

    private static InputBindingDto binding(String dataField) {
        return InputBindingDto.builder()
                .templateVariable("v")
                .dataField(dataField)
                .build();
    }

    @Test
    @DisplayName("FILE placeholder resolves a String to a DIAL ref")
    void fileReferenceIsConverted() {
        when(dialFileRefResolver.resolveToDialRef("@ef/suites/s1/a.pdf")).thenReturn("files/bucket/suites/s1/a.pdf");

        Object result = resolver.resolve("@ef/suites/s1/a.pdf", "file", null, null);

        assertThat(result).isEqualTo("files/bucket/suites/s1/a.pdf");
    }

    @Test
    @DisplayName("FILE-column placeholder without hint resolves a String to a DIAL ref")
    void hintlessFileColumnIsConverted() {
        when(dialFileRefResolver.resolveToDialRef("@ef/suites/s1/a.pdf")).thenReturn("files/bucket/suites/s1/a.pdf");

        Object result = resolver.resolve("@ef/suites/s1/a.pdf", null, binding("attachment"), SCHEMA);

        assertThat(result).isEqualTo("files/bucket/suites/s1/a.pdf");
    }

    @Test
    @DisplayName("SKILL placeholder resolves a String to the canonical skill ref without DialFileRefResolver")
    void skillReferenceIsCanonicalised() {
        Object result = resolver.resolve(" skills/b/x/ ", "skill", null, null);

        assertThat(result).isEqualTo("skills/b/x");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("SKILL-column placeholder without hint is canonicalised")
    void hintlessSkillColumnIsCanonicalised() {
        Object result = resolver.resolve("skills/b/x/", null, binding("skill"), SCHEMA);

        assertThat(result).isEqualTo("skills/b/x");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("placeholder that is neither FILE nor SKILL is returned unchanged")
    void otherPlaceholderUnchanged() {
        assertThat(resolver.resolve("skills/b/x/", null, binding("title"), SCHEMA))
                .isEqualTo("skills/b/x/");
        assertThat(resolver.resolve("skills/b/x/", "string", binding("skill"), SCHEMA))
                .isEqualTo("skills/b/x/");
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("non-String value is returned unchanged for FILE and SKILL placeholders")
    void nonStringUnchanged() {
        assertThat(resolver.resolve(5, "skill", null, null)).isEqualTo(5);
        assertThat(resolver.resolve(5, "file", null, null)).isEqualTo(5);
        assertThat(resolver.resolve(null, "skill", null, null)).isNull();
        verify(dialFileRefResolver, never()).resolveToDialRef(any());
    }

    @Test
    @DisplayName("malformed SKILL value is returned unchanged")
    void malformedSkillUnchanged() {
        assertThat(resolver.resolve("not-a-skill", "skill", null, null)).isEqualTo("not-a-skill");
    }
}
