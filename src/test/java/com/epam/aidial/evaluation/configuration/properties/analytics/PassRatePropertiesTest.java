package com.epam.aidial.evaluation.configuration.properties.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PassRateProperties")
class PassRatePropertiesTest {

    private ValidatorFactory validatorFactory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("shouldPassValidationWhenDefaultDoesNotExceedMax")
    void shouldPassValidationWhenDefaultDoesNotExceedMax() {
        PassRateProperties properties = new PassRateProperties();
        properties.setDefaultLastN(10);
        properties.setMaxLastN(100);

        Set<ConstraintViolation<PassRateProperties>> violations = validator.validate(properties);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("shouldRejectWhenDefaultExceedsMax")
    void shouldRejectWhenDefaultExceedsMax() {
        PassRateProperties properties = new PassRateProperties();
        properties.setDefaultLastN(200);
        properties.setMaxLastN(100);

        Set<ConstraintViolation<PassRateProperties>> violations = validator.validate(properties);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("analytics.pass-rate.default-last-n must not exceed analytics.pass-rate.max-last-n");
    }
}
