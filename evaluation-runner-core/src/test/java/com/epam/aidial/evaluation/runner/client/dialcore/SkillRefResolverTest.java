package com.epam.aidial.evaluation.runner.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkillRefResolverTest {

    private final SkillRefResolver resolver = new SkillRefResolver();

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
