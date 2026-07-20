package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.service.domain.job.MultiTurnAssembler.AssembledMultiTurn;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MultiTurnAssembler (snapshot-time survivor selection / broken-multiTurn rules)")
class MultiTurnAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MultiTurnAssembler assembler = new MultiTurnAssembler(objectMapper);
    private final UUID multiTurnId = UUID.randomUUID();

    @Test
    @DisplayName("contiguous valid turns assemble into a runnable unit with ordered frozen turns")
    void contiguousTurnsAssemble() {
        List<TestCase> turns =
                List.of(turn(2, true, "{\"q\":\"c\"}"), turn(0, true, "{\"q\":\"a\"}"), turn(1, true, "{\"q\":\"b\"}"));

        AssembledMultiTurn result = assembler.assemble(turns, Set.of()).orElseThrow();

        assertThat(result.broken()).isFalse();
        assertThat(result.totalTurns()).isEqualTo(3);
        assertThat(result.multiTurnId()).isEqualTo(multiTurnId);
        JsonNode frozen = objectMapper.readTree(result.turnsJson());
        assertThat(frozen).hasSize(3);
        assertThat(frozen.get(0).get("turnIndex").asInt()).isEqualTo(0);
        assertThat(frozen.get(0).get("data").get("q").asString()).isEqualTo("a");
        assertThat(frozen.get(2).get("turnIndex").asInt()).isEqualTo(2);
        assertThat(frozen.get(0).get("testCaseId").asString()).isNotBlank();
        assertThat(frozen.get(0).get("testCaseName").asString()).isNotBlank();
    }

    @Test
    @DisplayName("tail disable shortens the multiTurn to the surviving turns (not broken)")
    void tailDisableShortens() {
        TestCase t0 = turn(0, true, "{}");
        TestCase t1 = turn(1, true, "{}");
        TestCase t2 = turn(2, true, "{}");

        AssembledMultiTurn result =
                assembler.assemble(List.of(t0, t1, t2), Set.of(t2.getId())).orElseThrow();

        assertThat(result.broken()).isFalse();
        assertThat(result.totalTurns()).isEqualTo(2);
        assertThat(objectMapper.readTree(result.turnsJson())).hasSize(2);
    }

    @Test
    @DisplayName("disabling a middle turn is honored — survivors run with authored indices preserved")
    void middleDisableRuns() {
        TestCase t0 = turn(0, true, "{}");
        TestCase t1 = turn(1, true, "{}");
        TestCase t2 = turn(2, true, "{}");

        AssembledMultiTurn result =
                assembler.assemble(List.of(t0, t1, t2), Set.of(t1.getId())).orElseThrow();

        assertThat(result.broken()).isFalse();
        assertThat(result.totalTurns()).isEqualTo(2);
        JsonNode frozen = objectMapper.readTree(result.turnsJson());
        assertThat(frozen).hasSize(2);
        assertThat(frozen.get(0).get("turnIndex").asInt()).isEqualTo(0);
        assertThat(frozen.get(1).get("turnIndex").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a missing turn 0 no longer breaks the multiTurn — survivors run in order")
    void missingTurnZeroRuns() {
        AssembledMultiTurn result = assembler
                .assemble(List.of(turn(1, true, "{}"), turn(2, true, "{}")), Set.of())
                .orElseThrow();

        assertThat(result.broken()).isFalse();
        assertThat(result.totalTurns()).isEqualTo(2);
        JsonNode frozen = objectMapper.readTree(result.turnsJson());
        assertThat(frozen.get(0).get("turnIndex").asInt()).isEqualTo(1);
        assertThat(frozen.get(1).get("turnIndex").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("any invalid surviving turn breaks the whole multiTurn")
    void anyInvalidTurnBreaks() {
        AssembledMultiTurn result = assembler
                .assemble(List.of(turn(0, true, "{}"), turn(1, false, "{}")), Set.of())
                .orElseThrow();

        assertThat(result.broken()).isTrue();
        assertThat(result.totalTurns()).isZero();
    }

    @Test
    @DisplayName("an invalid turn that is disabled no longer breaks the multiTurn")
    void disabledInvalidTurnDoesNotBreak() {
        TestCase t0 = turn(0, true, "{}");
        TestCase t1 = turn(1, false, "{}");

        AssembledMultiTurn result =
                assembler.assemble(List.of(t0, t1), Set.of(t1.getId())).orElseThrow();

        assertThat(result.broken()).isFalse();
        assertThat(result.totalTurns()).isEqualTo(1);
    }

    @Test
    @DisplayName("a surviving turn count over the cap breaks the multiTurn")
    void overCapBreaks() {
        List<TestCase> turns = new ArrayList<>();
        for (int i = 0; i <= ValidationConstants.MAX_MULTI_TURN_TURNS; i++) {
            turns.add(turn(i, true, "{}"));
        }

        AssembledMultiTurn result = assembler.assemble(turns, Set.of()).orElseThrow();

        assertThat(result.broken()).isTrue();
        assertThat(result.totalTurns()).isZero();
    }

    @Test
    @DisplayName("all turns disabled leaves no survivors and yields no execution unit (skipped, not broken)")
    void allDisabledSkips() {
        TestCase t0 = turn(0, true, "{}");
        TestCase t1 = turn(1, true, "{}");

        assertThat(assembler.assemble(List.of(t0, t1), Set.of(t0.getId(), t1.getId())))
                .isEmpty();
    }

    private TestCase turn(int turnIndex, boolean valid, String data) {
        return TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .testCaseName("conv / turn " + turnIndex)
                .multiTurnId(multiTurnId)
                .turnIndex(turnIndex)
                .valid(valid)
                .data(data)
                .build();
    }
}
