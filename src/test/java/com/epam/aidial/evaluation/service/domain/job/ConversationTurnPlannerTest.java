package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConversationTurnPlanner")
class ConversationTurnPlannerTest {

    private final ConversationTurnPlanner planner = new ConversationTurnPlanner();

    private static InputBindingDto dataBinding(String templateVariable, String dataField) {
        return InputBindingDto.builder()
                .templateVariable(templateVariable)
                .dataField(dataField)
                .build();
    }

    private static InputBindingDto constantBinding(String templateVariable, Object value) {
        return InputBindingDto.builder()
                .templateVariable(templateVariable)
                .constantValue(value)
                .build();
    }

    @Test
    @DisplayName("errors when no bound column is array-valued")
    void noArrayColumnError() {
        final TurnPlan plan = planner.plan(List.of(dataBinding("q", "question")), Map.of("question", "only a scalar"));

        assertThat(plan.hasError()).isTrue();
        assertThat(plan.error()).contains("array-valued");
    }

    @Test
    @DisplayName("errors when array-valued bound columns have different lengths")
    void mismatchedArrayLengthsError() {
        final TurnPlan plan = planner.plan(
                List.of(dataBinding("q", "questions"), dataBinding("c", "contexts")),
                Map.of("questions", List.of("a", "b"), "contexts", List.of("x")));

        assertThat(plan.hasError()).isTrue();
        assertThat(plan.error()).contains("equal length");
    }

    @Test
    @DisplayName("errors when the array-valued bound column is empty")
    void emptyArrayColumnError() {
        final TurnPlan plan = planner.plan(List.of(dataBinding("q", "questions")), Map.of("questions", List.of()));

        assertThat(plan.hasError()).isTrue();
    }

    @Test
    @DisplayName("errors when the turn count exceeds MAX_CONVERSATION_STEPS")
    void turnCountOverCapError() {
        final List<Object> tooMany = IntStream.range(0, ValidationConstants.MAX_CONVERSATION_STEPS + 1)
                .mapToObj(i -> (Object) ("turn-" + i))
                .toList();

        final TurnPlan plan = planner.plan(List.of(dataBinding("q", "questions")), Map.of("questions", tooMany));

        assertThat(plan.hasError()).isTrue();
        assertThat(plan.error()).contains("maximum");
    }

    @Test
    @DisplayName("resolves turn count and iterating fields for a valid single array length")
    void resolvesValidPlan() {
        final TurnPlan plan = planner.plan(
                List.of(dataBinding("q", "questions"), constantBinding("sys", "system prompt")),
                Map.of("questions", List.of("a", "b", "c"), "system", "ignored scalar"));

        assertThat(plan.hasError()).isFalse();
        assertThat(plan.turnCount()).isEqualTo(3);
        assertThat(plan.iteratingFields()).containsExactly("questions");
    }

    @Test
    @DisplayName("project() picks element i of array columns and broadcasts other columns unchanged")
    void projectPicksElementAndBroadcasts() {
        final Map<String, Object> data = Map.of("questions", List.of("a", "b"), "system", "hello");
        final TurnPlan plan = planner.plan(List.of(dataBinding("q", "questions")), data);

        final Map<String, Object> turn0 = plan.project(data, 0);
        final Map<String, Object> turn1 = plan.project(data, 1);

        assertThat(turn0).containsEntry("questions", "a").containsEntry("system", "hello");
        assertThat(turn1).containsEntry("questions", "b").containsEntry("system", "hello");
    }
}
