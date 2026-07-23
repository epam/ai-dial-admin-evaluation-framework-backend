package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MultiTurnFieldsValidatorTest {

    private MultiTurnFieldsValidator validator;

    @BeforeEach
    void setUp() {
        TestCaseProperties props = new TestCaseProperties();
        props.getMultiTurn().setMaxTurns(10);
        validator = new MultiTurnFieldsValidator(props);
    }

    @Test
    @DisplayName("data and multiTurnData together are rejected")
    void mutualExclusivity() {
        assertThatThrownBy(() -> validator.validateStructure(Map.of("prompt", "hi"), List.of(Map.of("prompt", "hi"))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("empty multiTurnData array is rejected")
    void emptyArrayRejected() {
        assertThatThrownBy(() -> validator.validateStructure(Map.of(), List.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("single-turn (data only) and multi-turn (multiTurnData only) are accepted")
    void validShapesAccepted() {
        assertThatCode(() -> validator.validateStructure(Map.of("prompt", "hi"), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateStructure(Map.of(), List.of(Map.of("prompt", "hi"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("getMaxTurns exposes the configured cap")
    void exposesCap() {
        assertThat(validator.getMaxTurns()).isEqualTo(10);
    }
}
