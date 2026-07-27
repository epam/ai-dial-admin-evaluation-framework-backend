package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InputBindingDto binding-source exclusivity")
class InputBindingDtoTest {

    @Test
    @DisplayName("dataField alone is valid")
    void dataFieldAloneIsValid() {
        assertThat(binding("question", null, null).isValidBinding()).isTrue();
    }

    @Test
    @DisplayName("constantValue alone is valid")
    void constantValueAloneIsValid() {
        assertThat(binding(null, "gpt-4", null).isValidBinding()).isTrue();
    }

    @Test
    @DisplayName("responseField alone is valid")
    void responseFieldAloneIsValid() {
        assertThat(binding(null, null, "session_id").isValidBinding()).isTrue();
    }

    @Test
    @DisplayName("dataField plus constantValue is invalid")
    void dataFieldAndConstantValueIsInvalid() {
        assertThat(binding("question", "gpt-4", null).isValidBinding()).isFalse();
    }

    @Test
    @DisplayName("dataField plus responseField is invalid")
    void dataFieldAndResponseFieldIsInvalid() {
        assertThat(binding("question", null, "session_id").isValidBinding()).isFalse();
    }

    @Test
    @DisplayName("constantValue plus responseField is invalid")
    void constantValueAndResponseFieldIsInvalid() {
        assertThat(binding(null, "gpt-4", "session_id").isValidBinding()).isFalse();
    }

    @Test
    @DisplayName("all three sources together is invalid")
    void allThreeIsInvalid() {
        assertThat(binding("question", "gpt-4", "session_id").isValidBinding()).isFalse();
    }

    @Test
    @DisplayName("none of the three sources is invalid")
    void noneIsInvalid() {
        assertThat(binding(null, null, null).isValidBinding()).isFalse();
    }

    @Test
    @DisplayName("a blank dataField does not count as a source, so blank-only is invalid")
    void blankDataFieldDoesNotCountAsSource() {
        assertThat(binding("   ", null, null).isValidBinding()).isFalse();
    }

    @Test
    @DisplayName("a blank responseField does not count as a source, so it does not collide with dataField")
    void blankResponseFieldDoesNotCountAsSource() {
        assertThat(binding("question", null, "   ").isValidBinding()).isTrue();
    }

    @Test
    @DisplayName("a false constantValue still counts as a source — only null means absent")
    void falseConstantValueCountsAsBindingSource() {
        assertThat(binding(null, false, null).isValidBinding()).isTrue();
        assertThat(binding("question", false, null).isValidBinding()).isFalse();
    }

    private static InputBindingDto binding(String dataField, Object constantValue, String responseField) {
        return InputBindingDto.builder()
                .templateVariable("var")
                .dataField(dataField)
                .constantValue(constantValue)
                .responseField(responseField)
                .build();
    }
}
