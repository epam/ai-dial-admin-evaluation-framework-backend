package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.service.domain.job.ConversationAssembler.AssembledConversation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ConversationAssembler (snapshot-time contiguity / broken-conversation rules)")
class ConversationAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationAssembler assembler = new ConversationAssembler(objectMapper);
    private final UUID conversationId = UUID.randomUUID();

    @Test
    @DisplayName("contiguous valid turns assemble into a runnable unit with ordered frozen turns")
    void contiguousTurnsAssemble() {
        List<TestCase> turns =
                List.of(turn(2, true, "{\"q\":\"c\"}"), turn(0, true, "{\"q\":\"a\"}"), turn(1, true, "{\"q\":\"b\"}"));

        AssembledConversation result = assembler.assemble(turns, Set.of());

        assertThat(result.broken()).isFalse();
        assertThat(result.totalTurns()).isEqualTo(3);
        assertThat(result.conversationId()).isEqualTo(conversationId);
        JsonNode frozen = objectMapper.readTree(result.turnsJson());
        assertThat(frozen).hasSize(3);
        // ordered by turnIndex regardless of input order
        assertThat(frozen.get(0).get("turnIndex").asInt()).isEqualTo(0);
        assertThat(frozen.get(0).get("data").get("q").asString()).isEqualTo("a");
        assertThat(frozen.get(2).get("turnIndex").asInt()).isEqualTo(2);
        // each frozen turn carries its own identity
        assertThat(frozen.get(0).get("testCaseId").asString()).isNotBlank();
        assertThat(frozen.get(0).get("testCaseName").asString()).isNotBlank();
    }

    @Test
    @DisplayName("tail-only disable truncates to the surviving contiguous prefix (not broken)")
    void tailDisableTruncates() {
        TestCase t0 = turn(0, true, "{}");
        TestCase t1 = turn(1, true, "{}");
        TestCase t2 = turn(2, true, "{}");

        AssembledConversation result = assembler.assemble(List.of(t0, t1, t2), Set.of(t2.getId()));

        assertThat(result.broken()).isFalse();
        assertThat(result.totalTurns()).isEqualTo(2);
        assertThat(objectMapper.readTree(result.turnsJson())).hasSize(2);
    }

    @Test
    @DisplayName("disabling a middle turn breaks the conversation (non-contiguous prefix)")
    void middleDisableBreaks() {
        TestCase t0 = turn(0, true, "{}");
        TestCase t1 = turn(1, true, "{}");
        TestCase t2 = turn(2, true, "{}");

        AssembledConversation result = assembler.assemble(List.of(t0, t1, t2), Set.of(t1.getId()));

        assertThat(result.broken()).isTrue();
        assertThat(result.totalTurns()).isZero();
        assertThat(result.turnsJson()).isNull();
    }

    @Test
    @DisplayName("a missing turn 0 breaks the conversation")
    void missingTurnZeroBreaks() {
        AssembledConversation result = assembler.assemble(List.of(turn(1, true, "{}"), turn(2, true, "{}")), Set.of());

        assertThat(result.broken()).isTrue();
        assertThat(result.totalTurns()).isZero();
    }

    @Test
    @DisplayName("any invalid turn breaks the whole conversation")
    void anyInvalidTurnBreaks() {
        AssembledConversation result = assembler.assemble(List.of(turn(0, true, "{}"), turn(1, false, "{}")), Set.of());

        assertThat(result.broken()).isTrue();
        assertThat(result.totalTurns()).isZero();
    }

    @Test
    @DisplayName("a surviving turn count over the cap breaks the conversation")
    void overCapBreaks() {
        List<TestCase> turns = new ArrayList<>();
        for (int i = 0; i <= ValidationConstants.MAX_CONVERSATION_TURNS; i++) {
            turns.add(turn(i, true, "{}"));
        }

        AssembledConversation result = assembler.assemble(turns, Set.of());

        assertThat(result.broken()).isTrue();
        assertThat(result.totalTurns()).isZero();
    }

    @Test
    @DisplayName("all turns disabled leaves no survivors and breaks the conversation")
    void allDisabledBreaks() {
        TestCase t0 = turn(0, true, "{}");
        TestCase t1 = turn(1, true, "{}");

        AssembledConversation result = assembler.assemble(List.of(t0, t1), Set.of(t0.getId(), t1.getId()));

        assertThat(result.broken()).isTrue();
        assertThat(result.totalTurns()).isZero();
    }

    private TestCase turn(int turnIndex, boolean valid, String data) {
        return TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .testCaseName("conv / turn " + turnIndex)
                .conversationId(conversationId)
                .turnIndex(turnIndex)
                .valid(valid)
                .data(data)
                .build();
    }
}
