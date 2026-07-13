package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConversationFieldsValidator (row-based multi-turn write-time validation)")
class ConversationFieldsValidatorTest {

    private final ConversationFieldsValidator validator = new ConversationFieldsValidator();

    @Test
    @DisplayName("both fields null (single-turn) is valid")
    void bothNullIsValid() {
        assertThatCode(() -> validator.validate(null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("both fields present with an in-range turnIndex is valid")
    void bothPresentIsValid() {
        assertThatCode(() -> validator.validate(UUID.randomUUID(), 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("conversationId without turnIndex is rejected (both-or-neither)")
    void conversationIdWithoutTurnIndexRejected() {
        assertThatThrownBy(() -> validator.validate(UUID.randomUUID(), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("provided together");
    }

    @Test
    @DisplayName("turnIndex without conversationId is rejected (both-or-neither)")
    void turnIndexWithoutConversationIdRejected() {
        assertThatThrownBy(() -> validator.validate(null, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("provided together");
    }

    @Test
    @DisplayName("negative turnIndex is rejected")
    void negativeTurnIndexRejected() {
        assertThatThrownBy(() -> validator.validate(UUID.randomUUID(), -1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(">= 0");
    }

    @Test
    @DisplayName("turnIndex at the cap is rejected (must be strictly less than MAX_CONVERSATION_TURNS)")
    void turnIndexAtCapRejected() {
        assertThatThrownBy(() -> validator.validate(UUID.randomUUID(), ValidationConstants.MAX_CONVERSATION_TURNS))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("less than " + ValidationConstants.MAX_CONVERSATION_TURNS);
    }

    @Test
    @DisplayName("turnIndex one below the cap is valid")
    void turnIndexJustBelowCapValid() {
        assertThatCode(() -> validator.validate(UUID.randomUUID(), ValidationConstants.MAX_CONVERSATION_TURNS - 1))
                .doesNotThrowAnyException();
    }
}
