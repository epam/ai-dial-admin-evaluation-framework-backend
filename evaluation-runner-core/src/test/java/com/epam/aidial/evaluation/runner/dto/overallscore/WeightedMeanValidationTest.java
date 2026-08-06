package com.epam.aidial.evaluation.runner.dto.overallscore;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WeightedMean validation")
class WeightedMeanValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("valid weighted mean passes validation")
    void validWeightedMean_passesValidation() {
        WeightedMean dto = new WeightedMean(List.of(new WeightedMetric("RAG Retrieval", "F1", new BigDecimal("1.0"))));

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("empty weights list fails validation")
    void emptyWeights_failsValidation() {
        WeightedMean dto = new WeightedMean(List.of());

        Set<ConstraintViolation<WeightedMean>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("blank metricName fails validation (cascaded via @Valid)")
    void blankMetricName_failsValidation() {
        WeightedMean dto = new WeightedMean(List.of(new WeightedMetric(" ", "F1", new BigDecimal("1.0"))));

        Set<ConstraintViolation<WeightedMean>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("null weight fails validation (cascaded via @Valid)")
    void nullWeight_failsValidation() {
        WeightedMean dto = new WeightedMean(List.of(new WeightedMetric("RAG Retrieval", "F1", null)));

        Set<ConstraintViolation<WeightedMean>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("null expression fails validation for CustomFunction")
    void nullExpression_failsValidation() {
        CustomFunction dto = new CustomFunction(null);

        Set<ConstraintViolation<CustomFunction>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }
}
