package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.TestCaseDataScopeResolver.ScopePlacement;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TestCaseDataScopeResolver")
class TestCaseDataScopeResolverTest {

    private static final String PER_TURN_MESSAGE = "Field 'prompt' is per-turn but currently specified on a test "
            + "case level. Re-create column for correct data attachment";
    private static final String SHARED_MESSAGE = "Field 'tags' is shared (test-case-level) but values are "
            + "specified on turn level. Re-create column for correct data attachment";

    // prompt varies per turn; tags is constant across the whole test case.
    private static final List<FieldDefinitionDto> SCHEMA = List.of(field("prompt", true), field("tags", false));

    private final TestCaseDataScopeResolver resolver = new TestCaseDataScopeResolver(new TestCaseFieldScopeResolver());

    private static FieldDefinitionDto field(String name, boolean perTurn) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.STRING)
                .perTurn(perTurn)
                .build();
    }

    @Test
    @DisplayName("shared field found in a turn is flagged as misplaced")
    void shouldFlagSharedFieldFoundInTurn_asMisplaced() {
        ScopePlacement placement = resolver.inspect(Map.of(), List.of(Map.of("tags", "x")), SCHEMA);

        assertThat(placement.warnings()).hasSize(1);
        ValidationWarningDto warning = placement.warnings().get(0);
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
        assertThat(warning.getFieldName()).isEqualTo("tags");
        assertThat(warning.getPath()).isEqualTo("$.multiTurnData[0].tags");
        assertThat(warning.getTurnIndex()).isEqualTo(0);
        assertThat(warning.getMessage()).isEqualTo(SHARED_MESSAGE);
    }

    @Test
    @DisplayName("per-turn field found in data is flagged as misplaced")
    void shouldFlagPerTurnFieldFoundInData_asMisplaced() {
        ScopePlacement placement = resolver.inspect(Map.of("prompt", "x"), List.of(Map.of()), SCHEMA);

        assertThat(placement.warnings()).hasSize(1);
        ValidationWarningDto warning = placement.warnings().get(0);
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.INVALID_SCOPE);
        assertThat(warning.getFieldName()).isEqualTo("prompt");
        assertThat(warning.getPath()).isEqualTo("$.data.prompt");
        assertThat(warning.getTurnIndex()).isNull();
        assertThat(warning.getMessage()).isEqualTo(PER_TURN_MESSAGE);
    }

    @Test
    @DisplayName("both directions misplaced at once are each reported, data-side first")
    void shouldReportBothDirections_whenCaseViolatesBothWays() {
        ScopePlacement placement = resolver.inspect(Map.of("prompt", "x"), List.of(Map.of("tags", "y")), SCHEMA);

        assertThat(placement.warnings()).hasSize(2);
        assertThat(placement.warnings().get(0).getFieldName()).isEqualTo("prompt");
        assertThat(placement.warnings().get(0).getMessage()).isEqualTo(PER_TURN_MESSAGE);
        assertThat(placement.warnings().get(1).getFieldName()).isEqualTo("tags");
        assertThat(placement.warnings().get(1).getMessage()).isEqualTo(SHARED_MESSAGE);
    }

    @Test
    @DisplayName("requireCorrectScope throws the data-side message when both directions are misplaced")
    void shouldThrowDataSideMessage_whenRequireCorrectScopeSeesBothDirections() {
        assertThatThrownBy(
                        () -> resolver.requireCorrectScope(Map.of("prompt", "x"), List.of(Map.of("tags", "y")), SCHEMA))
                .isInstanceOf(ValidationException.class)
                .hasMessage(PER_TURN_MESSAGE);
    }

    @Test
    @DisplayName("an undeclared key is left in place and produces no warning")
    void shouldLeaveUndeclaredKey_unwarnedAndInPlace() {
        ScopePlacement placement = resolver.inspect(Map.of("mystery", 1), List.of(Map.of("secret", 2)), SCHEMA);

        assertThat(placement.warnings()).isEmpty();
        assertThat(placement.shared()).containsEntry("mystery", 1);
        assertThat(placement.turns().get(0)).containsEntry("secret", 2);
        assertThat(placement.misplacedFields()).isEmpty();
    }

    @Test
    @DisplayName("a null schema declares no fields, so nothing can be misplaced")
    void shouldReportNothing_whenSchemaIsNull() {
        ScopePlacement placement = resolver.inspect(Map.of("prompt", "x"), List.of(Map.of("tags", "y")), null);

        assertThat(placement.warnings()).isEmpty();
        assertThat(placement.misplacedFields()).isEmpty();
    }

    @Test
    @DisplayName("an empty schema declares no fields, so nothing can be misplaced")
    void shouldReportNothing_whenSchemaIsEmpty() {
        ScopePlacement placement = resolver.inspect(Map.of("prompt", "x"), List.of(Map.of("tags", "y")), List.of());

        assertThat(placement.warnings()).isEmpty();
        assertThat(placement.misplacedFields()).isEmpty();
    }

    @Test
    @DisplayName("a null data map yields a null shared result")
    void shouldReturnNullSharedResult_whenDataIsNull() {
        ScopePlacement placement = resolver.inspect(null, List.of(Map.of("prompt", "ok")), SCHEMA);

        assertThat(placement.shared()).isNull();
        assertThat(placement.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a null turns list yields a null turns result")
    void shouldReturnNullTurnsResult_whenTurnsIsNull() {
        ScopePlacement placement = resolver.inspect(Map.of("tags", "ok"), null, SCHEMA);

        assertThat(placement.turns()).isNull();
        assertThat(placement.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a null turn element is preserved as null at the same index")
    void shouldPreserveNullTurnElement_atSameIndex() {
        List<Map<String, Object>> turns = Arrays.asList(null, Map.of("prompt", "ok"));

        ScopePlacement placement = resolver.inspect(Map.of(), turns, SCHEMA);

        assertThat(placement.turns()).hasSize(2);
        assertThat(placement.turns().get(0)).isNull();
        assertThat(placement.turns().get(1)).containsEntry("prompt", "ok");
        assertThat(placement.warnings()).isEmpty();
    }

    @Test
    @DisplayName("the misplaced key is stripped from the shared result while other keys remain")
    void shouldStripMisplacedKeyFromSharedResult_butKeepOthers() {
        ScopePlacement placement = resolver.inspect(Map.of("prompt", "p1", "tags", "t1"), List.of(Map.of()), SCHEMA);

        assertThat(placement.shared()).doesNotContainKey("prompt");
        assertThat(placement.shared()).containsEntry("tags", "t1");
    }

    @Test
    @DisplayName("the misplaced key is stripped from the turn result while other keys remain")
    void shouldStripMisplacedKeyFromTurnResult_butKeepOthers() {
        ScopePlacement placement = resolver.inspect(Map.of(), List.of(Map.of("prompt", "p2", "tags", "t2")), SCHEMA);

        assertThat(placement.turns().get(0)).doesNotContainKey("tags");
        assertThat(placement.turns().get(0)).containsEntry("prompt", "p2");
    }

    @Test
    @DisplayName("misplacedFields collects field names from both directions")
    void shouldCollectMisplacedFieldNames_fromBothDirections() {
        ScopePlacement placement = resolver.inspect(Map.of("prompt", "x"), List.of(Map.of("tags", "y")), SCHEMA);

        assertThat(placement.misplacedFields()).containsExactlyInAnyOrder("prompt", "tags");
    }

    @Test
    @DisplayName("the caller's data map is not mutated when a misplacement is found")
    void shouldNotMutateCallerDataMap_whenMisplacementFound() {
        Map<String, Object> data = Map.of("prompt", "x");

        resolver.inspect(data, List.of(Map.of()), SCHEMA);

        assertThat(data).containsEntry("prompt", "x");
    }

    @Test
    @DisplayName("the caller's turn map is not mutated when a misplacement is found")
    void shouldNotMutateCallerTurnMap_whenMisplacementFound() {
        Map<String, Object> turn = Map.of("tags", "y");
        List<Map<String, Object>> turns = List.of(turn);

        resolver.inspect(Map.of(), turns, SCHEMA);

        assertThat(turn).containsEntry("tags", "y");
    }

    @Test
    @DisplayName("turn arity and order are preserved after stripping")
    void shouldPreserveTurnArityAndOrder_afterStripping() {
        List<Map<String, Object>> turns = List.of(Map.of(), Map.of("tags", "a"), Map.of());

        ScopePlacement placement = resolver.inspect(Map.of(), turns, SCHEMA);

        assertThat(placement.turns()).hasSize(3);
        assertThat(placement.turns().get(0)).isEmpty();
        assertThat(placement.turns().get(1)).doesNotContainKey("tags");
        assertThat(placement.turns().get(2)).isEmpty();
    }

    @Test
    @DisplayName("a shared field present in N turns produces N warnings with correct turn index and path")
    void shouldProduceOneWarningPerOccurrence_whenSharedFieldPresentInMultipleTurns() {
        List<Map<String, Object>> turns = List.of(Map.of("tags", "a"), Map.of("tags", "b"), Map.of("tags", "c"));

        ScopePlacement placement = resolver.inspect(Map.of(), turns, SCHEMA);

        assertThat(placement.warnings()).hasSize(3);
        for (int i = 0; i < 3; i++) {
            ValidationWarningDto warning = placement.warnings().get(i);
            assertThat(warning.getTurnIndex()).isEqualTo(i);
            assertThat(warning.getPath()).isEqualTo("$.multiTurnData[" + i + "].tags");
        }
    }

    @Test
    @DisplayName("requireCorrectScope does not throw when nothing is misplaced")
    void shouldNotThrow_whenRequireCorrectScopeFindsNoMisplacement() {
        assertThatCode(() -> resolver.requireCorrectScope(Map.of("tags", "a"), List.of(Map.of("prompt", "hi")), SCHEMA))
                .doesNotThrowAnyException();
    }
}
