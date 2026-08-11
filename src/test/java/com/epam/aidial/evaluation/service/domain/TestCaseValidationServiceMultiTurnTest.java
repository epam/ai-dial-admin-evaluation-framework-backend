package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
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
                new TestCaseFieldScopeResolver(),
                new TestCaseDataScopeResolver(new TestCaseFieldScopeResolver()));
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

    @Test
    @DisplayName(
            "a required shared field misplaced into a turn yields a single INVALID_SCOPE warning, no required-missing twin")
    void sharedRequiredFieldMisplacedIntoTurn() {
        // "system" is shared+required but is placed in the turn instead of data; data omits it entirely.
        ValidationResult r = run(Map.of(), List.of(Map.of("prompt", "q0", "system", "s")));

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(1);
        ValidationWarningDto warning = r.getWarnings().get(0);
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
        assertThat(warning.getFieldName()).isEqualTo("system");
        assertThat(warning.getTurnIndex()).isEqualTo(0);
        assertThat(warning.getPath()).isEqualTo("$.multiTurnData[0].system");
    }

    @Test
    @DisplayName(
            "a required per-turn field misplaced into data yields a single INVALID_SCOPE warning, no required-missing twin")
    void perTurnRequiredFieldMisplacedIntoData() {
        // "prompt" is per-turn+required but is placed in data instead of the turn; the turn omits it.
        ValidationResult r = run(Map.of("system", "s", "prompt", "p1"), List.of(Map.of()));

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(1);
        ValidationWarningDto warning = r.getWarnings().get(0);
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
        assertThat(warning.getFieldName()).isEqualTo("prompt");
        assertThat(warning.getTurnIndex()).isNull();
        assertThat(warning.getPath()).isEqualTo("$.data.prompt");
    }

    @Test
    @DisplayName("both directions misplaced at once are each reported once, with no required-missing twins")
    void bothDirectionsMisplacedAtOnce() {
        // "prompt" (per-turn) sits in data; "system" (shared) sits in the turn. Neither bucket has its
        // required field where it belongs, but the scope warning alone must explain why.
        ValidationResult r = run(Map.of("prompt", "p1"), List.of(Map.of("system", "s")));

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(2);
        assertThat(r.getWarnings())
                .anySatisfy(w -> {
                    assertThat(w.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
                    assertThat(w.getFieldName()).isEqualTo("prompt");
                    assertThat(w.getTurnIndex()).isNull();
                    assertThat(w.getPath()).isEqualTo("$.data.prompt");
                })
                .anySatisfy(w -> {
                    assertThat(w.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
                    assertThat(w.getFieldName()).isEqualTo("system");
                    assertThat(w.getTurnIndex()).isEqualTo(0);
                    assertThat(w.getPath()).isEqualTo("$.multiTurnData[0].system");
                });
    }

    @Test
    @DisplayName(
            "an undeclared key stays an unknown-field warning with an unchanged message and a bucket-identifying path")
    void undeclaredKeyStaysUnknownFieldWarning() {
        ValidationResult r = run(Map.of("system", "s", "mystery", "x"), List.of(Map.of("prompt", "q0", "secret", "y")));

        assertThat(r.isValid()).isFalse();
        List<ValidationWarningDto> unknown = r.getWarnings().stream()
                .filter(w -> w.getCode() == ValidationWarningCode.ADDITIONAL)
                .toList();
        assertThat(unknown).hasSize(2);
        assertThat(unknown)
                .anySatisfy(w -> {
                    assertThat(w.getFieldName()).isEqualTo("mystery");
                    assertThat(w.getMessage()).isEqualTo("Unknown data field 'mystery'");
                    assertThat(w.getPath()).isEqualTo("$.data.mystery");
                    assertThat(w.getTurnIndex()).isNull();
                })
                .anySatisfy(w -> {
                    assertThat(w.getFieldName()).isEqualTo("secret");
                    assertThat(w.getMessage()).isEqualTo("Unknown data field 'secret'");
                    assertThat(w.getPath()).isEqualTo("$.multiTurnData[0].secret");
                    assertThat(w.getTurnIndex()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("a scope warning survives max-warnings-per-case truncation ahead of a shared-bucket warning")
    void scopeWarningSurvivesWarningCapTruncation() {
        when(validationProperties.getMaxWarningsPerCase()).thenReturn(1);

        // Shared data carries an undeclared key ("mystery"), which alone produces a shared-bucket
        // "Unknown data field" warning. "system" (shared, required) is additionally misplaced into turn 0,
        // producing one INVALID_SCOPE warning. With the cap at 1, INVALID_SCOPE must be the one that
        // survives truncation — not the shared-bucket warning it is seeded ahead of.
        ValidationResult r = run(Map.of("mystery", "a"), List.of(Map.of("prompt", "q0", "system", "s")));

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(1);
        assertThat(r.getWarnings().get(0).getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
        assertThat(r.getWarnings().get(0).getFieldName()).isEqualTo("system");
    }

    @Test
    @DisplayName("a per-turn required field stray in data but legitimately present in every turn yields only the "
            + "INVALID_SCOPE warning, no Unknown data field for the turns")
    void perTurnFieldStrayInDataButLegitimateInTurns() {
        // "prompt" (per-turn, required) has a stray copy in data; every turn also legitimately carries it.
        ValidationResult r =
                run(Map.of("system", "s", "prompt", "stray"), List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(1);
        ValidationWarningDto warning = r.getWarnings().get(0);
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
        assertThat(warning.getFieldName()).isEqualTo("prompt");
        assertThat(warning.getTurnIndex()).isNull();
        assertThat(warning.getPath()).isEqualTo("$.data.prompt");
    }

    @Test
    @DisplayName("a shared required field legitimately in data but stray in one turn yields only the INVALID_SCOPE "
            + "warning, specifically no Unknown data field against the legitimately-placed value")
    void sharedFieldLegitimateInDataButStrayInTurn() {
        // "system" (shared, required) is legitimately present in data; a stray copy also sits in turn 0.
        ValidationResult r = run(Map.of("system", "s"), List.of(Map.of("prompt", "q0", "system", "stray")));

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(1);
        ValidationWarningDto warning = r.getWarnings().get(0);
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
        assertThat(warning.getFieldName()).isEqualTo("system");
        assertThat(warning.getTurnIndex()).isEqualTo(0);
        assertThat(warning.getPath()).isEqualTo("$.multiTurnData[0].system");
        assertThat(r.getWarnings())
                .noneMatch(w -> w.getCode() == ValidationWarningCode.ADDITIONAL && "$.data.system".equals(w.getPath()));
    }

    @Test
    @DisplayName("a genuine type mismatch on a legitimately-placed value survives even when the same field name "
            + "is misplaced elsewhere, proving the sub-schema is not gutted")
    void typeMismatchSurvivesForFieldMisplacedElsewhere() {
        // "system" (shared, STRING, required) holds a wrong-type value in data (its legitimate bucket);
        // a second, stray copy of "system" also sits in turn 0.
        ValidationResult r = run(Map.of("system", 123), List.of(Map.of("prompt", "q0", "system", "another-stray")));

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(2);
        assertThat(r.getWarnings())
                .anySatisfy(w -> {
                    assertThat(w.getCode()).isEqualTo(ValidationWarningCode.TYPE);
                    assertThat(w.getFieldName()).isEqualTo("system");
                    assertThat(w.getPath()).isEqualTo("$.data.system");
                })
                .anySatisfy(w -> {
                    assertThat(w.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
                    assertThat(w.getFieldName()).isEqualTo("system");
                    assertThat(w.getTurnIndex()).isEqualTo(0);
                    assertThat(w.getPath()).isEqualTo("$.multiTurnData[0].system");
                });
    }
}
