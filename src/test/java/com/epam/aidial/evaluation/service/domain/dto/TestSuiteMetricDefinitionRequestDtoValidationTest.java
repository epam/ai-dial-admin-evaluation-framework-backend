package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TestSuiteMetricDefinitionRequestDto validation")
class TestSuiteMetricDefinitionRequestDtoValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("valid dto passes validation")
    void validDto_passesValidation() {
        var dto = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Accuracy")
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("name containing colon fails validation with colon-pattern message")
    void nameContainingColon_failsValidation() {
        var dto = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Acc:uracy")
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .build();

        Set<ConstraintViolation<TestSuiteMetricDefinitionRequestDto>> violations = validator.validate(dto);
        assertThat(violations).hasSize(1);
        ConstraintViolation<TestSuiteMetricDefinitionRequestDto> v =
                violations.iterator().next();
        assertThat(v.getPropertyPath().toString()).isEqualTo("name");
        assertThat(v.getMessage()).contains("':'");
    }

    @Test
    @DisplayName("name with family-prefix collision fails validation")
    void nameWithFamilyPrefix_failsValidation() {
        var dto = TestSuiteMetricDefinitionRequestDto.builder()
                .name("metric:Accuracy")
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .build();

        Set<ConstraintViolation<TestSuiteMetricDefinitionRequestDto>> violations = validator.validate(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    @DisplayName("blank name fails only NotBlank, not the colon pattern")
    void blankName_failsOnlyNotBlank() {
        var dto = TestSuiteMetricDefinitionRequestDto.builder()
                .name("")
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .build();

        Set<ConstraintViolation<TestSuiteMetricDefinitionRequestDto>> violations = validator.validate(dto);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsOnly("name");
        assertThat(violations).extracting(ConstraintViolation::getMessage).noneMatch(msg -> msg.contains("':'"));
    }
}
