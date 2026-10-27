package com.epam.aidial.evaluation.runner.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkillRefResolverTest {

    private static final List<FieldDefinitionDto> SKILL_SCHEMA = List.of(
            FieldDefinitionDto.builder()
                    .name("skill")
                    .type(SchemaFieldType.SKILL)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("attachment")
                    .type(SchemaFieldType.FILE)
                    .build());

    private final SkillRefResolver resolver = new SkillRefResolver(new BindingTypeResolver());

    private static InputBindingDto dataBinding(String dataField) {
        return InputBindingDto.builder()
                .templateVariable("s")
                .dataField(dataField)
                .build();
    }

    @Test
    @DisplayName("hintless placeholder bound to a SKILL column is a skill reference")
    void hintlessBoundToSkillColumnIsSkill() {
        assertThat(resolver.isSkillReference(null, dataBinding("skill"), SKILL_SCHEMA))
                .isTrue();
    }

    @Test
    @DisplayName("hintless placeholder bound to a FILE column is not a skill reference")
    void hintlessBoundToFileColumnIsNotSkill() {
        assertThat(resolver.isSkillReference(null, dataBinding("attachment"), SKILL_SCHEMA))
                .isFalse();
    }

    @Test
    @DisplayName("explicit skill hint is a skill reference without a binding or schema")
    void explicitSkillHintIsSkillWithoutSchema() {
        assertThat(resolver.isSkillReference("skill", null, null)).isTrue();
    }

    @Test
    @DisplayName("skill hint is case-insensitive and trimmed")
    void skillHintIsCaseInsensitiveAndTrimmed() {
        assertThat(resolver.isSkillReference(" SKILL ", null, SKILL_SCHEMA)).isTrue();
        assertThat(resolver.isSkillReference("Skill", dataBinding("attachment"), SKILL_SCHEMA))
                .isTrue();
    }

    @Test
    @DisplayName("explicit non-skill hint wins over a SKILL column")
    void explicitNonSkillHintWinsOverSkillColumn() {
        assertThat(resolver.isSkillReference("string", dataBinding("skill"), SKILL_SCHEMA))
                .isFalse();
        assertThat(resolver.isSkillReference("file", dataBinding("skill"), SKILL_SCHEMA))
                .isFalse();
    }

    @Test
    @DisplayName("constantValue-only binding without hint is not a skill reference")
    void constantValueBindingWithoutHintIsNotSkill() {
        final InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("s")
                .constantValue("skills/b/x")
                .build();

        assertThat(resolver.isSkillReference(null, binding, SKILL_SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("null schema or no binding is not a skill reference when no hint")
    void nullSchemaOrNoBindingIsNotSkill() {
        assertThat(resolver.isSkillReference(null, dataBinding("skill"), null)).isFalse();
        assertThat(resolver.isSkillReference(null, null, SKILL_SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("well-formed reference is valid and its canonical form is itself")
    void wellFormedReference() {
        assertThat(resolver.validate("skills/my-bucket/team/summariser")).isEmpty();
        assertThat(resolver.toCanonicalRef("skills/my-bucket/team/summariser"))
                .isEqualTo("skills/my-bucket/team/summariser");
    }

    @Test
    @DisplayName("surrounding whitespace and one trailing slash are normalised")
    void whitespaceAndTrailingSlashNormalised() {
        assertThat(resolver.validate("  skills/my-bucket/summariser/  ")).isEmpty();
        assertThat(resolver.toCanonicalRef("  skills/my-bucket/summariser/  "))
                .isEqualTo("skills/my-bucket/summariser");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "my-bucket/summariser",
                "Skills/my-bucket/summariser",
                "files/my-bucket/summariser",
                "/skills/my-bucket/summariser",
                "",
                "   "
            })
    @DisplayName("missing or wrong prefix is malformed")
    void wrongPrefixIsMalformed(String value) {
        assertThat(resolver.validate(value)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills/my-bucket", "skills/my-bucket/", "skills/", "skills"})
    @DisplayName("bucket-only reference is malformed")
    void bucketOnlyIsMalformed(String value) {
        assertThat(resolver.validate(value)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills/b//x", "skills/b/./x", "skills/b/../x", "skills/./x", "skills/b/x//"})
    @DisplayName("empty, dot and dot-dot segments are malformed")
    void emptyAndDotSegmentsAreMalformed(String value) {
        assertThat(resolver.validate(value)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skills/b\\x", "skills/b/x\ty", "skills/b/x\u0000"})
    @DisplayName("backslash and control characters are malformed")
    void forbiddenCharactersAreMalformed(String value) {
        assertThat(resolver.validate(value)).isPresent();
    }

    @Test
    @DisplayName("null is malformed and canonicalisation passes it through")
    void nullIsMalformed() {
        assertThat(resolver.validate(null)).isPresent();
        assertThat(resolver.toCanonicalRef(null)).isNull();
    }

    @Test
    @DisplayName("malformed value is returned unchanged by canonicalisation")
    void malformedValueUnchanged() {
        assertThat(resolver.toCanonicalRef("not-a-skill")).isEqualTo("not-a-skill");
        assertThat(resolver.toCanonicalRef(" skills/b ")).isEqualTo(" skills/b ");
    }

    @Test
    @DisplayName("segment characters beyond the structural rules are not restricted")
    void segmentCharactersNotRestricted() {
        assertThat(resolver.validate("skills/my bucket/ünï cödé:1.0")).isEmpty();
    }
}
