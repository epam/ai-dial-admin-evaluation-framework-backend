package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Assembles the turns of a single conversation into one runnable execution unit, applying the row-based
 * multi-turn selection rules at snapshot time. The input {@code turns} are the conversation's
 * <b>filter-matching</b> turns (the suite's {@code testCaseFilter} is applied row-level in SQL upstream,
 * like disable); when the suite has no filter this is every turn. Exclusion (the suite's
 * {@code disabledTestCaseIds}) is applied <b>first</b>, and every subsequent rule is evaluated over the
 * <b>surviving</b> turns only — a disabled turn has no influence on the outcome:
 *
 * <ul>
 *   <li>No surviving turns (e.g. every turn disabled) → the conversation contributes <b>no execution
 *       unit</b> at all ({@link Optional#empty()}); it is not a broken unit, it simply drops out of the run.
 *   <li>Any invalid turn ({@code is_valid = false}) among the survivors → the whole conversation is broken.
 *   <li>More than {@link ValidationConstants#MAX_CONVERSATION_TURNS} survivors → broken.
 * </ul>
 *
 * <p>Ordering and sequencing are <b>not</b> integrity concerns: the survivors run in ascending authored
 * {@code turn_index} order with their authored indices preserved (no renumbering), and gaps are allowed — a
 * turn disabled or filtered out at the start, middle, or end simply drops, and the remaining turns run. A
 * missing turn 0 or a hole no longer breaks the conversation.
 *
 * <p>A runnable unit carries the ordered surviving turns (as {@code turnsJson}) and {@code totalTurns} = the
 * surviving count. The turns keep their authored {@code turn_index}, so the maximum authored surviving index
 * (needed for turn position — {@code turn.last} — under gaps) is recomputed by the executor from the frozen
 * turns; it is not stored separately. A broken unit carries {@code broken = true} and {@code totalTurns = 0};
 * the executor turns it into a single {@code 0/0} ERROR result row without invoking the model.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ConversationAssembler {

    private final ObjectMapper objectMapper;

    /**
     * Assembles a conversation's (filter-matching) turns into an execution unit, or {@link Optional#empty()}
     * when no turn survives exclusion — a fully-disabled conversation contributes nothing to the run.
     */
    public Optional<AssembledConversation> assemble(List<TestCase> turns, Set<UUID> excludedIds) {
        final UUID conversationId = turns.getFirst().getConversationId();

        final List<TestCase> survivors = turns.stream()
                .filter(t -> !excludedIds.contains(t.getId()))
                .sorted(Comparator.comparingInt(
                        (TestCase t) -> t.getTurnIndex() == null ? Integer.MAX_VALUE : t.getTurnIndex()))
                .toList();

        // Every turn excluded (disabled) → this conversation is deselected entirely, not a broken unit.
        if (survivors.isEmpty()) {
            return Optional.empty();
        }

        // Rules apply to survivors only: a disabled turn (invalid or not) has no influence on the outcome.
        // Ordering/sequencing is NOT an integrity concern — a missing turn 0, a gap, or a filtered-out
        // start/middle turn simply yields a shorter survivor list, run in ascending authored turn_index order.
        final boolean anyInvalid = survivors.stream().anyMatch(t -> !t.isValid());
        final boolean broken = anyInvalid || survivors.size() > ValidationConstants.MAX_CONVERSATION_TURNS;

        final TestCase representative = survivors.getFirst();

        return Optional.of(AssembledConversation.builder()
                .conversationId(conversationId)
                .broken(broken)
                .totalTurns(broken ? 0 : survivors.size())
                .representativeTestCaseId(representative.getId())
                .representativeTestCaseName(representative.getTestCaseName())
                .representativeTestCaseData(representative.getData())
                .turnsJson(broken ? null : serializeTurns(survivors))
                .build());
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

    private JsonNode parseData(String data) {
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
