package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.client.dialcore.SkillRefResolver;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("TestCaseValidationService — SKILL field validation (DIAL skill refs)")
@ExtendWith(MockitoExtension.class)
class TestCaseValidationServiceSkillTest {

    private static final String FIELD = "skill";

    @Mock
    private TemplateVariableExtractor templateVariableExtractor;

    @Mock
    private ValidationProperties validationProperties;

    @Mock
    private FileRefValidator fileRefValidator;

    @Mock
    private TestCaseProperties testCaseProperties;

    private TestCaseValidationService service;

    @BeforeEach
    void setUp() {
        service = new TestCaseValidationService(
                templateVariableExtractor,
                validationProperties,
                fileRefValidator,
                new SkillRefResolver(new BindingTypeResolver()),
                testCaseProperties,
                new TestCaseFieldScopeResolver(),
                new TestCaseDataScopeResolver(new TestCaseFieldScopeResolver()));
        when(validationProperties.getMaxWarningsPerCase()).thenReturn(100);
        when(templateVariableExtractor.extract(any())).thenReturn(List.of());
    }

    private ValidationResult validate(Object value, boolean required) {
        final Map<String, Object> data = new HashMap<>();
        data.put(FIELD, value);
        final List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name(FIELD)
                .type(SchemaFieldType.SKILL)
                .required(required)
                .build());
        return service.validateTestCase(data, schema, null, List.of(), false, null);
    }

    private static List<ValidationWarningDto> warningsFor(ValidationResult result, ValidationWarningCode code) {
        return result.getWarnings().stream()
                .filter(w -> w.getCode() == code && FIELD.equals(w.getFieldName()))
                .toList();
    }

    @Test
    @DisplayName("well-formed skill reference produces no warning")
    void wellFormedValueProducesNoWarning() {
        final ValidationResult result = validate("skills/my-bucket/summariser", false);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("malformed skill reference produces a TYPE warning with the 'SKILL field' prefix")
    void malformedValueProducesTypeWarning() {
        final ValidationResult result = validate("my-bucket/summariser", false);

        assertThat(result.isValid()).isFalse();
        assertThat(warningsFor(result, ValidationWarningCode.TYPE))
                .singleElement()
                .satisfies(w -> {
                    assertThat(w.getMessage()).startsWith("SKILL field 'skill': ");
                    assertThat(w.getPath()).isEqualTo("$.data.skill");
                });
    }

    @Test
    @DisplayName("skill reference in a bucket unrelated to the suite or dataset produces no ownership warning")
    void otherBucketProducesNoOwnershipWarning() {
        final ValidationResult result = validate("skills/some-other-bucket/x", false);

        assertThat(result.getWarnings()).isEmpty();
        verifyNoInteractions(fileRefValidator);
    }

    @Test
    @DisplayName("non-string SKILL value produces the standard type-mismatch warning and no format warning")
    void nonStringValueIsTypeMismatch() {
        final ValidationResult result = validate(42, false);

        assertThat(warningsFor(result, ValidationWarningCode.TYPE))
                .singleElement()
                .satisfies(w -> assertThat(w.getMessage()).contains("has schema type SKILL but value is Integer"));
    }

    @Test
    @DisplayName("null value in a non-required SKILL field produces no warning")
    void nullNonRequiredProducesNoWarning() {
        final ValidationResult result = validate(null, false);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("blank value in a non-required SKILL field produces no warning")
    void blankNonRequiredProducesNoWarning() {
        final ValidationResult result = validate("   ", false);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("blank value in a required SKILL field produces the required-field warning")
    void blankRequiredProducesRequiredWarning() {
        final ValidationResult result = validate("   ", true);

        assertThat(warningsFor(result, ValidationWarningCode.REQUIRED))
                .singleElement()
                .satisfies(w -> assertThat(w.getMessage()).isEqualTo("Required field 'skill' is missing from data"));
        assertThat(warningsFor(result, ValidationWarningCode.TYPE)).isEmpty();
    }

    @Test
    @DisplayName("null value in a required SKILL field produces the required-field warning")
    void nullRequiredProducesRequiredWarning() {
        final ValidationResult result = validate(null, true);

        assertThat(warningsFor(result, ValidationWarningCode.REQUIRED)).hasSize(1);
    }
}
