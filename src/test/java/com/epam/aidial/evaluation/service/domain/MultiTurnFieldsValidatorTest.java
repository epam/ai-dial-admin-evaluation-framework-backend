package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MultiTurnFieldsValidator (row-based multi-turn write-time validation)")
class MultiTurnFieldsValidatorTest {

    private final MultiTurnFieldsValidator validator = new MultiTurnFieldsValidator();

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
    @DisplayName("multiTurnId without turnIndex is rejected (both-or-neither)")
    void multiTurnIdWithoutTurnIndexRejected() {
        assertThatThrownBy(() -> validator.validate(UUID.randomUUID(), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("provided together");
    }

    @Test
    @DisplayName("turnIndex without multiTurnId is rejected (both-or-neither)")
    void turnIndexWithoutMultiTurnIdRejected() {
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
    @DisplayName("a large turnIndex at or above the cap is accepted (no write-time upper bound)")
    void largeTurnIndexAccepted() {
        assertThatCode(() -> validator.validate(UUID.randomUUID(), ValidationConstants.MAX_MULTI_TURN_TURNS))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(UUID.randomUUID(), 10_000)).doesNotThrowAnyException();
    }
}
