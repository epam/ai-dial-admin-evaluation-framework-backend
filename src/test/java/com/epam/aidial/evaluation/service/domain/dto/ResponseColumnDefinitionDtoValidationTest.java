package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ResponseColumnDefinitionDto validation")
class ResponseColumnDefinitionDtoValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("valid dto passes validation")
    void validDto_passesValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("choices[0].message.content")
                .build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("blank name fails validation")
    void blankName_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("")
                .expression("choices[0].message.content")
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("name");
    }

    @Test
    @DisplayName("null name fails validation")
    void nullName_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name(null)
                .expression("choices[0]")
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("name");
    }

    @Test
    @DisplayName("blank expression fails validation")
    void blankExpression_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("")
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("expression");
    }

    @Test
    @DisplayName("null expression fails validation")
    void nullExpression_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression(null)
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("expression");
    }

    @Test
    @DisplayName("null type passes validation — type is optional")
    void nullType_passesValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("choices[0]")
                .type(null)
                .build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("null displayName passes validation — displayName is optional")
    void nullDisplayName_passesValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("choices[0]")
                .displayName(null)
                .build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("name at 255 chars passes validation")
    void nameAt255_passesValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("x".repeat(255))
                .expression("choices[0]")
                .build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("name exceeding 255 chars fails validation")
    void nameExceeding255_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("x".repeat(256))
                .expression("choices[0]")
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("name");
    }

    @Test
    @DisplayName("expression at 2000 chars passes validation")
    void expressionAt2000_passesValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("x".repeat(2000))
                .build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("expression exceeding 2000 chars fails validation")
    void expressionExceeding2000_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("x".repeat(2001))
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("expression");
    }

    @Test
    @DisplayName("displayName at 255 chars passes validation")
    void displayNameAt255_passesValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("choices[0]")
                .displayName("x".repeat(255))
                .build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("displayName exceeding 255 chars fails validation")
    void displayNameExceeding255_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("choices[0]")
                .displayName("x".repeat(256))
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("displayName");
    }

    @Test
    @DisplayName("name containing double colon fails validation with double-colon-pattern message")
    void nameContainingDoubleColon_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("with::colon")
                .expression("choices[0]")
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).hasSize(1);
        ConstraintViolation<ResponseColumnDefinitionDto> v =
                violations.iterator().next();
        assertThat(v.getPropertyPath().toString()).isEqualTo("name");
        assertThat(v.getMessage()).contains("'::'");
    }

    @Test
    @DisplayName("name containing a single colon passes validation")
    void nameContainingSingleColon_passesValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("with:colon")
                .expression("choices[0]")
                .build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("name with family-separator collision fails validation")
    void nameWithFamilySeparator_failsValidation() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("response::foo")
                .expression("choices[0]")
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    @DisplayName("blank name fails only NotBlank, not the double-colon pattern")
    void blankName_failsOnlyNotBlank() {
        var dto = ResponseColumnDefinitionDto.builder()
                .name("")
                .expression("choices[0]")
                .build();

        Set<ConstraintViolation<ResponseColumnDefinitionDto>> violations = validator.validate(dto);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsOnly("name");
        assertThat(violations).extracting(ConstraintViolation::getMessage).noneMatch(msg -> msg.contains("'::'"));
    }
}
