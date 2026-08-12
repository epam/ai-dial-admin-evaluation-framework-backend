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
import java.util.ArrayList;
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

    // A schema declaring no per-turn column at all: every field is shared, so a case's turns can hold
    // nothing legitimately.
    private static final List<FieldDefinitionDto> ALL_SHARED =
            List.of(field("system", false, true), field("tags", false, false));

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
        // "prompt" is declared per-turn (just not required), so the schema DOES declare a per-turn column
        // and the no-per-turn-columns warning must stay silent.
        List<FieldDefinitionDto> perTurnDeclared = List.of(field("prompt", true, false), field("system", false, false));
        ValidationResult r = service.validateMultiTurn(
                Map.of(), List.of(Map.of(), Map.of()), perTurnDeclared, null, List.of(), false, null);
        assertThat(r.isValid()).isTrue();
        assertThat(r.getWarnings()).noneMatch(w -> "$.multiTurnData".equals(w.getPath()));
    }

    @Test
    @DisplayName("a case carrying turns in an all-shared schema is invalidated by one case-level warning")
    void turnsWithoutPerTurnColumnsInvalidateTheCase() {
        // The turn maps are empty because any key in them would add its own warning (a declared shared
        // field → INVALID_SCOPE, an undeclared key → Unknown data field), and this test pins the
        // no-per-turn-columns warning as the *only* one. Both combinations are covered separately below.
        ValidationResult r = service.validateMultiTurn(
                Map.of("system", "s"), List.of(Map.of(), Map.of()), ALL_SHARED, null, List.of(), false, null);

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(1);
        ValidationWarningDto warning = r.getWarnings().get(0);
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.ADDITIONAL);
        assertThat(warning.getPath()).isEqualTo("$.multiTurnData");
        assertThat(warning.getFieldName()).isNull();
        assertThat(warning.getTurnIndex()).isNull();
        assertThat(warning.getMessage())
                .isEqualTo("Test case has 2 turns but the dataset schema declares no per-turn columns; "
                        + "turn data cannot be attached");
    }

    @Test
    @DisplayName("misplaced turn values and the no-per-turn-columns warning are both reported")
    void misplacementAndNoPerTurnColumnsWarningCoexist() {
        // "system" is declared shared, so each turn holding it is a misplacement — and the schema declares
        // no per-turn column at all, which is why no turn could ever hold it legitimately.
        ValidationResult r = service.validateMultiTurn(
                Map.of("system", "s"),
                List.of(Map.of("system", "a"), Map.of("system", "b")),
                ALL_SHARED,
                null,
                List.of(),
                false,
                null);

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(3);
        assertThat(r.getWarnings())
                .filteredOn(w -> w.getCode() == ValidationWarningCode.INVALID_SCOPE)
                .hasSize(2)
                .extracting(ValidationWarningDto::getTurnIndex)
                .containsExactlyInAnyOrder(0, 1);
        assertThat(r.getWarnings()).anySatisfy(w -> {
            assertThat(w.getCode()).isEqualTo(ValidationWarningCode.ADDITIONAL);
            assertThat(w.getPath()).isEqualTo("$.multiTurnData");
            assertThat(w.getTurnIndex()).isNull();
        });
    }

    @Test
    @DisplayName("the no-per-turn-columns warning survives max-warnings-per-case truncation")
    void noPerTurnColumnsWarningSurvivesWarningCapTruncation() {
        when(validationProperties.getMaxWarningsPerCase()).thenReturn(5);

        // Five turns each misplacing the declared-shared "system" fill the cap exactly, so an *appended*
        // case-level warning would be the one entry truncation drops. It is prepended, so it survives.
        List<Map<String, Object>> turns = List.of(
                Map.of("system", "a"),
                Map.of("system", "b"),
                Map.of("system", "c"),
                Map.of("system", "d"),
                Map.of("system", "e"));
        ValidationResult r =
                service.validateMultiTurn(Map.of("system", "s"), turns, ALL_SHARED, null, List.of(), false, null);

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(5);
        assertThat(r.getWarnings()).anyMatch(w -> "$.multiTurnData".equals(w.getPath()));
    }

    @Test
    @DisplayName("an over-cap case in an all-shared schema carries both case-level warnings")
    void overCapAndNoPerTurnColumnsWarningsCoexist() {
        // 11 empty turns against a 10-turn cap: both case-level defects apply and are independent, so both
        // warnings must be present at $.multiTurnData (empty turn maps keep field-level noise out).
        List<Map<String, Object>> turns = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            turns.add(Map.of());
        }

        ValidationResult r =
                service.validateMultiTurn(Map.of("system", "s"), turns, ALL_SHARED, null, List.of(), false, null);

        assertThat(r.isValid()).isFalse();
        assertThat(r.getWarnings()).hasSize(2);
        assertThat(r.getWarnings()).allSatisfy(w -> {
            assertThat(w.getPath()).isEqualTo("$.multiTurnData");
            assertThat(w.getCode()).isEqualTo(ValidationWarningCode.ADDITIONAL);
            assertThat(w.getTurnIndex()).isNull();
        });
        assertThat(r.getWarnings())
                .extracting(ValidationWarningDto::getMessage)
                .anySatisfy(m -> assertThat(m).contains("exceeding the maximum of 10"))
                .anySatisfy(m -> assertThat(m).contains("11 turns", "no per-turn columns"));
    }

    @Test
    @DisplayName("a single-turn case in an all-shared schema is unaffected by the no-per-turn-columns rule")
    void singleTurnCaseInAllSharedSchemaStaysValid() {
        ValidationResult r = service.validateTestCase(Map.of("system", "s"), ALL_SHARED, null, List.of(), false, null);

        assertThat(r.isValid()).isTrue();
        assertThat(r.getWarnings()).isEmpty();
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
