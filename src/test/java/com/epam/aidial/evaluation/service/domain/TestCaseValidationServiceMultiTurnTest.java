package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("TestCaseValidationService — scope-aware multi-turn validation")
@ExtendWith(MockitoExtension.class)
class TestCaseValidationServiceMultiTurnTest {

    @Mock
    private TemplateVariableExtractor templateVariableExtractor;

    @Mock
    private ValidationProperties validationProperties;

    @Mock
    private FileRefValidator fileRefValidator;

    private TestCaseValidationService service;

    private static FieldDefinitionDto field(String name, boolean perTurn, boolean required) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.STRING)
                .perTurn(perTurn)
                .required(required)
                .build();
    }

    // prompt: per-turn required; system: shared required; tags: shared optional.
    private static final List<FieldDefinitionDto> SCHEMA =
            List.of(field("prompt", true, true), field("system", false, true), field("tags", false, false));

    @BeforeEach
    void setUp() {
        TestCaseProperties props = new TestCaseProperties();
        props.getMultiTurn().setMaxTurns(10);
        service = new TestCaseValidationService(
                templateVariableExtractor,
                validationProperties,
                fileRefValidator,
                props,
                new TestCaseFieldScopeResolver());
        when(validationProperties.getMaxWarningsPerCase()).thenReturn(100);
        when(templateVariableExtractor.extract(any())).thenReturn(List.of());
    }

    private ValidationResult run(Map<String, Object> shared, List<Map<String, Object>> turns) {
        return service.validateMultiTurn(shared, turns, SCHEMA, null, List.of(), false, null);
    }

    @Test
    @DisplayName("valid when shared required present and every turn has its per-turn required field")
    void valid() {
        ValidationResult r =
                run(Map.of("system", "s", "tags", "a"), List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));
        assertThat(r.isValid()).isTrue();
        assertThat(r.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("missing shared required field invalidates with a non-turn warning")
    void missingSharedRequired() {
        ValidationResult r = run(Map.of(), List.of(Map.of("prompt", "q0")));
        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).anyMatch(w -> w.getTurnIndex() == null && "system".equals(w.getFieldName()));
    }

    @Test
    @DisplayName("a per-turn required field missing in one turn tags that turn index")
    void perTurnRequiredMissing() {
        ValidationResult r = run(Map.of("system", "s"), List.of(Map.of("prompt", "q0"), Map.of()));
        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings())
                .anyMatch(w -> Integer.valueOf(1).equals(w.getTurnIndex()) && "prompt".equals(w.getFieldName()));
    }

    @Test
    @DisplayName("empty per-turn maps are valid when no per-turn field is required")
    void emptyPerTurnAllowed() {
        List<FieldDefinitionDto> allShared = List.of(field("prompt", true, false), field("system", false, false));
        ValidationResult r = service.validateMultiTurn(
                Map.of(), List.of(Map.of(), Map.of()), allShared, null, List.of(), false, null);
        assertThat(r.isValid()).isTrue();
    }
}
