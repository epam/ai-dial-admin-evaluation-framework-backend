package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import com.epam.aidial.evaluation.service.domain.RequestSpec;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ChainExecutor")
class ChainExecutorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final UUID TEST_CASE_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = JsonMapperConfiguration.createJsonMapper();
    private final QuietJsonService jsonService = new QuietJsonService(objectMapper);

    @Test
    @DisplayName("a three-request chain writes three rows with contiguous indices and resolved labels")
    void threeRequestChainWritesThreeRows() {
        RecordingStepExecutor step = new RecordingStepExecutor();
        ChainExecutor executor = executorWith(step);

        List<TestCaseRunResult> results = executor.execute(
                input(), context(chain("setup", "configure", "invoke")), 0, "trace-1", FIXED_CLOCK.millis());

        assertThat(results).hasSize(3);
        assertThat(results).extracting(TestCaseRunResult::getRequestIndex).containsExactly(0, 1, 2);
        assertThat(results)
                .extracting(TestCaseRunResult::getRequestLabel)
                .containsExactly("setup", "configure", "invoke");
        assertThat(results).allSatisfy(row -> {
            assertThat(row.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(row.getTestCaseId()).isEqualTo(TEST_CASE_ID);
            assertThat(row.getRunIndex()).isZero();
            assertThat(row.getTraceId()).isEqualTo("trace-1");
        });
    }

    @Test
    @DisplayName("a later request sees an earlier request's extracted value in the accumulated map")
    void laterRequestConsumesEarlierValue() {
        RecordingStepExecutor step = new RecordingStepExecutor();
        step.extractedPerIndex.put(0, Map.of("session_id", "abc"));
        ChainExecutor executor = executorWith(step);

        executor.execute(input(), context(chain("setup", "invoke")), 0, "t", FIXED_CLOCK.millis());

        assertThat(step.seenResponseValues.get(0)).isEmpty();
        assertThat(step.seenResponseValues.get(1)).containsEntry("session_id", "abc");
    }

    @Test
    @DisplayName("request 3 can consume request 0's value, not merely its predecessor's")
    void nonAdjacentAccumulationWorks() {
        RecordingStepExecutor step = new RecordingStepExecutor();
        step.extractedPerIndex.put(0, Map.of("session_id", "abc"));
        ChainExecutor executor = executorWith(step);

        executor.execute(input(), context(chain("a", "b", "c", "d")), 0, "t", FIXED_CLOCK.millis());

        assertThat(step.seenResponseValues.get(3)).containsEntry("session_id", "abc");
    }

    @Test
    @DisplayName("each row carries only its own request's extracted columns, not the accumulated set")
    void extractedColumnsAreRequestLocal() {
        RecordingStepExecutor step = new RecordingStepExecutor();
        step.extractedPerIndex.put(0, Map.of("session_id", "abc"));
        step.extractedPerIndex.put(1, Map.of("answer", "42"));
        ChainExecutor executor = executorWith(step);

        List<TestCaseRunResult> results =
                executor.execute(input(), context(chain("setup", "invoke")), 0, "t", FIXED_CLOCK.millis());

        assertThat(results.get(0).getExtractedColumns()).contains("session_id").doesNotContain("answer");
        assertThat(results.get(1).getExtractedColumns()).contains("answer").doesNotContain("session_id");
    }

    @Test
    @DisplayName("failure at request k yields k SUCCESS rows plus one ERROR row and no later rows")
    void failFastAtRequestK() {
        RecordingStepExecutor step = new RecordingStepExecutor();
        step.failAtIndex = 2;
        ChainExecutor executor = executorWith(step);

        List<TestCaseRunResult> results =
                executor.execute(input(), context(chain("a", "b", "c", "d")), 0, "t", FIXED_CLOCK.millis());

        assertThat(results).hasSize(3);
        assertThat(results)
                .extracting(TestCaseRunResult::getExecutionStatus)
                .containsExactly(ExecutionStatus.SUCCESS, ExecutionStatus.SUCCESS, ExecutionStatus.FAILED);
        assertThat(results).extracting(TestCaseRunResult::getRequestIndex).containsExactly(0, 1, 2);
        assertThat(step.invokedIndices).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("an unresolvable dependency persists an ERROR row naming the missing column and aborts")
    void unresolvableDependencyAborts() {
        RecordingStepExecutor step = new RecordingStepExecutor();
        step.unresolvableAtIndex = 1;
        ChainExecutor executor = executorWith(step);

        List<TestCaseRunResult> results =
                executor.execute(input(), context(chain("a", "b", "c")), 0, "t", FIXED_CLOCK.millis());

        assertThat(results).hasSize(2);
        assertThat(results.get(1).getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(results.get(1).getLogDetails()).contains("session_id");
        assertThat(step.invokedIndices).containsExactly(0, 1);
    }

    @Test
    @DisplayName("every multi-request row carries inert turn columns, since multi-turn is excluded")
    void turnColumnsAreInert() {
        ChainExecutor executor = executorWith(new RecordingStepExecutor());

        List<TestCaseRunResult> results =
                executor.execute(input(), context(chain("a", "b")), 0, "t", FIXED_CLOCK.millis());

        assertThat(results).allSatisfy(row -> {
            assertThat(row.getTurnIndex()).isZero();
            assertThat(row.getTotalTurns()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("an unchecked failure in a step becomes an ERROR row rather than escaping the chain")
    void uncheckedStepFailureBecomesErrorRow() {
        RecordingStepExecutor step = new RecordingStepExecutor();
        step.throwAtIndex = 1;
        ChainExecutor executor = executorWith(step);

        List<TestCaseRunResult> results =
                executor.execute(input(), context(chain("a", "b", "c")), 0, "t", FIXED_CLOCK.millis());

        assertThat(results).hasSize(2);
        assertThat(results.get(1).getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(results.get(1).getResponseBody()).contains("CHAIN_STEP_ERROR");
    }

    @Test
    @DisplayName("cancellation before a request stops the chain without writing a row for it")
    void cancellationWritesNoRowForUnstartedRequest() {
        RecordingStepExecutor step = new RecordingStepExecutor();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        step.cancelAfterIndex = 0;
        step.cancellationSignal = cancelled;
        ChainExecutor executor = executorWith(step);

        List<TestCaseRunResult> results =
                executor.execute(input(), context(chain("a", "b", "c"), cancelled), 0, "t", FIXED_CLOCK.millis());

        assertThat(results).hasSize(1);
        assertThat(step.invokedIndices).containsExactly(0);
    }

    @Test
    @DisplayName("an empty chain writes no rows rather than throwing")
    void emptyChainWritesNoRows() {
        ChainExecutor executor = executorWith(new RecordingStepExecutor());

        assertThat(executor.execute(input(), context(List.of()), 0, "t", FIXED_CLOCK.millis()))
                .isEmpty();
    }

    // ---- harness ----

    private ChainExecutor executorWith(ChainStepExecutor step) {
        return new ChainExecutor(new ChainStepExecutorRegistry(List.of(step)), jsonService, FIXED_CLOCK);
    }

    private static List<RequestSpec> chain(String... labels) {
        List<RequestSpec> chain = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            chain.add(new RequestSpec(
                    i,
                    labels[i],
                    ChainRequestType.HTTP,
                    null,
                    null,
                    List.of(),
                    List.of(ResponseColumnDefinitionDto.builder()
                            .name("col" + i)
                            .expression("$.col" + i)
                            .build())));
        }
        return chain;
    }

    private static EvaluationContext context(List<RequestSpec> chain) {
        return context(chain, new AtomicBoolean(false));
    }

    private static EvaluationContext context(List<RequestSpec> chain, AtomicBoolean cancellationSignal) {
        return EvaluationContext.builder()
                .runId(RUN_ID)
                .suiteId(SUITE_ID)
                .chain(chain)
                .cancellationSignal(cancellationSignal)
                .createdAtMs(FIXED_CLOCK.millis())
                .build();
    }

    private static TestCaseRunInput input() {
        return TestCaseRunInput.builder()
                .runId(RUN_ID)
                .testCaseId(TEST_CASE_ID)
                .testCaseName("case-1")
                .testCaseData("{\"question\":\"q\"}")
                .build();
    }

    /**
     * Stub step executor that records what each step was handed and can be told to fail, throw, report an
     * unresolvable dependency, or trip a cancellation signal at a chosen chain index.
     */
    private static final class RecordingStepExecutor implements ChainStepExecutor {

        private final List<Integer> invokedIndices = new ArrayList<>();
        private final Map<Integer, Map<String, Object>> seenResponseValues = new java.util.HashMap<>();
        private final Map<Integer, Map<String, Object>> extractedPerIndex = new java.util.HashMap<>();
        private Integer failAtIndex;
        private Integer throwAtIndex;
        private Integer unresolvableAtIndex;
        private Integer cancelAfterIndex;
        private AtomicBoolean cancellationSignal;

        @Override
        public ChainRequestType supportedType() {
            return ChainRequestType.HTTP;
        }

        @Override
        public ChainStepOutcome execute(ChainStepRequest step) {
            int index = step.request().index();
            invokedIndices.add(index);
            seenResponseValues.put(index, step.responseValues());

            if (cancelAfterIndex != null && index == cancelAfterIndex && cancellationSignal != null) {
                cancellationSignal.set(true);
            }
            if (throwAtIndex != null && index == throwAtIndex) {
                throw new IllegalStateException("boom at " + index);
            }
            if (unresolvableAtIndex != null && index == unresolvableAtIndex) {
                return ChainStepOutcome.unresolvedDependency(null, List.of("session_id"));
            }
            if (failAtIndex != null && index == failAtIndex) {
                return ChainStepOutcome.failed(ExecutionStatus.FAILED, 500, "{}", "{\"error\":\"x\"}", 1);
            }

            Map<String, Object> extracted = extractedPerIndex.getOrDefault(index, Map.of());
            return new ChainStepOutcome(
                    ExecutionStatus.SUCCESS,
                    200,
                    "{}",
                    "{\"ok\":true}",
                    0,
                    toJson(extracted),
                    "[]",
                    extracted,
                    List.of());
        }

        private static String toJson(Map<String, Object> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{");
            map.forEach(
                    (k, v) -> sb.append('"').append(k).append("\":\"").append(v).append("\","));
            sb.setLength(sb.length() - 1);
            return sb.append('}').toString();
        }
    }
}
