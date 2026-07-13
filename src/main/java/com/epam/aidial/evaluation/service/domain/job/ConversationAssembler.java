package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Assembles the turns of a single conversation (all rows sharing a {@code conversation_id}) into one
 * runnable execution unit, applying the row-based multi-turn selection rules at snapshot time:
 *
 * <ul>
 *   <li>Any invalid turn ({@code is_valid = false}) → the whole conversation is broken.
 *   <li>Disable is tail-only: after removing the excluded (disabled) turn ids, the survivors MUST form a
 *       contiguous prefix {@code 0..k} (start at 0, no gap, no duplicate index); a middle hole → broken.
 *   <li>No surviving turns, or more than {@link ValidationConstants#MAX_CONVERSATION_TURNS} survivors → broken.
 * </ul>
 *
 * <p>A runnable unit carries the ordered surviving turns (as {@code turnsJson}) and {@code totalTurns} = the
 * surviving count. A broken unit carries {@code broken = true} and {@code totalTurns = 0}; the executor turns
 * it into a single {@code 0/0} ERROR result row without invoking the model.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ConversationAssembler {

    private final ObjectMapper objectMapper;

    public AssembledConversation assemble(List<TestCase> turns, Set<UUID> excludedIds) {
        final UUID conversationId = turns.get(0).getConversationId();
        final boolean anyInvalid = turns.stream().anyMatch(t -> !t.isValid());

        final List<TestCase> survivors = turns.stream()
                .filter(t -> !excludedIds.contains(t.getId()))
                .sorted(Comparator.comparingInt(
                        (TestCase t) -> t.getTurnIndex() == null ? Integer.MAX_VALUE : t.getTurnIndex()))
                .toList();

        final boolean contiguous = isContiguousFromZero(survivors);
        final boolean broken = anyInvalid
                || survivors.isEmpty()
                || !contiguous
                || survivors.size() > ValidationConstants.MAX_CONVERSATION_TURNS;

        final TestCase representative = survivors.isEmpty() ? turns.get(0) : survivors.get(0);

        return AssembledConversation.builder()
                .conversationId(conversationId)
                .broken(broken)
                .totalTurns(broken ? 0 : survivors.size())
                .representativeTestCaseId(representative.getId())
                .representativeTestCaseName(representative.getTestCaseName())
                .representativeTestCaseData(representative.getData())
                .turnsJson(broken ? null : serializeTurns(survivors))
                .build();
    }

    /** True when the survivors' turn indexes are exactly {@code 0, 1, ..., size-1} (start at 0, no gap, no dup). */
    private static boolean isContiguousFromZero(List<TestCase> survivors) {
        for (int i = 0; i < survivors.size(); i++) {
            final Integer turnIndex = survivors.get(i).getTurnIndex();
            if (turnIndex == null || turnIndex != i) {
                return false;
            }
        }
        return true;
    }

    private String serializeTurns(List<TestCase> survivors) {
        final ArrayNode array = objectMapper.createArrayNode();
        for (TestCase turn : survivors) {
            final ObjectNode node = objectMapper.createObjectNode();
            node.put("testCaseId", turn.getId().toString());
            node.put("testCaseName", turn.getTestCaseName());
            node.put("turnIndex", turn.getTurnIndex());
            node.set("data", parseData(turn.getData()));
            array.add(node);
        }
        try {
            return objectMapper.writeValueAsString(array);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize assembled conversation turns", e);
        }
    }

    private tools.jackson.databind.JsonNode parseData(String data) {
        if (data == null || data.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(data);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to parse test case data JSON during conversation assembly", e);
        }
    }

    @Builder
    public record AssembledConversation(
            UUID conversationId,
            boolean broken,
            int totalTurns,
            UUID representativeTestCaseId,
            String representativeTestCaseName,
            String representativeTestCaseData,
            String turnsJson) {}
}
