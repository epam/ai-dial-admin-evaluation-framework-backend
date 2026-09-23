package com.epam.aidial.evaluation.runner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

@DisplayName("CallerCredential")
class CallerCredentialTest {

    @Test
    @DisplayName("bearer/apiKey factories return null for blank or null input")
    void factoriesReturnNullForBlankOrNullInput() {
        assertThat(CallerCredential.bearer(null)).isNull();
        assertThat(CallerCredential.bearer("  ")).isNull();
        assertThat(CallerCredential.apiKey(null)).isNull();
        assertThat(CallerCredential.apiKey("  ")).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("canonical constructor rejects a blank value so no instance can render 'Bearer null'")
    void canonicalConstructorRejectsBlankValue(String blank) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CallerCredential(blank, CredentialKind.BEARER))
                .withMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("canonical constructor rejects a null kind")
    void canonicalConstructorRejectsNullKind() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CallerCredential("abc123", null))
                .withMessageContaining("kind");
    }

    @Test
    @DisplayName("toString masks the raw value")
    void toStringMasksValue() {
        String rendered = CallerCredential.apiKey("super-secret-key").toString();

        assertThat(rendered).doesNotContain("super-secret-key").isEqualTo("CallerCredential[kind=API_KEY, value=***]");
    }

    @Test
    @DisplayName("headerName returns Authorization for a bearer credential and Api-Key for an API-key credential")
    void headerNameSelectsHeaderByKind() {
        assertThat(CallerCredential.bearer("abc123").headerName()).isEqualTo(HttpHeaders.AUTHORIZATION);
        assertThat(CallerCredential.apiKey("abc123").headerName()).isEqualTo(CallerCredential.API_KEY_HEADER);
    }

    @Test
    @DisplayName(
            "headerValue prefixes Bearer for a bearer credential and passes the raw value for an API-key credential")
    void headerValueFormatsValueByKind() {
        assertThat(CallerCredential.bearer("abc123").headerValue()).isEqualTo("Bearer abc123");
        assertThat(CallerCredential.apiKey("abc123").headerValue()).isEqualTo("abc123");
    }
}
