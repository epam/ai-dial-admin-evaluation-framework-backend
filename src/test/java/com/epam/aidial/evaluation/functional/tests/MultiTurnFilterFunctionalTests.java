package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for the suite-level {@code testCaseFilter} applied at run time as ALL-turns-match: a
 * multi-turn case runs only when every turn satisfies the filter. Satisfaction for a missing/null per-turn
 * field follows the operator's polarity — a positive predicate is not satisfied (so the case is excluded),
 * a negated one is (GH #141). Single-turn cases behave as the trivial 1-turn case, and a suite with no
 * filter runs every valid case unchanged.
 */
@DisplayName("Multi-turn Suite Filter Functional Tests")
public abstract class MultiTurnFilterFunctionalTests extends AbstractMultiTurnFunctionalTest {

    /** {@code data::category eq <value>} as a raw StructuredQuery filter map. */
    private static Map<String, Object> categoryEquals(String value) {
        return Map.of(
                "op",
                "eq",
                "args",
                List.of(
                        Map.of("type", "field", "name", "data::category"),
                        Map.of("type", "value", "value_type", "string", "value", value)));
    }

    /** {@code data::category nc <value>} as a raw StructuredQuery filter map. */
    private static Map<String, Object> categoryNotContains(String value) {
        return Map.of(
                "op",
                "nc",
                "args",
                List.of(
                        Map.of("type", "field", "name", "data::category"),
                        Map.of("type", "value", "value_type", "string", "value", value)));
    }

    /** Seeds the standard mix of cases into the dataset: 3 multi-turn + 2 single-turn. */
    private void seedStandardCases(UUID datasetId) {
        createMultiTurnCase(
                datasetId,
                "mt-all-match",
                List.of(Map.of("prompt", "q0", "category", "A"), Map.of("prompt", "q1", "category", "A")));
        createMultiTurnCase(
                datasetId,
                "mt-one-fails",
                List.of(Map.of("prompt", "q0", "category", "A"), Map.of("prompt", "q1", "category", "B")));
        createMultiTurnCase(
                datasetId,
                "mt-missing-field",
                List.of(Map.of("prompt", "q0", "category", "A"), Map.of("prompt", "q1")));
        createSingleTurnCase(datasetId, "single-match", Map.of("prompt", "s0", "category", "A"));
        createSingleTurnCase(datasetId, "single-nomatch", Map.of("prompt", "s1", "category", "B"));
    }

    private void stubDeployment() {
        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> chatReply("reply-" + call.getAndIncrement()));
    }

    private Map<String, Long> rowsPerTestCase(UUID runId) {
        return analyticsTestDataHelper.findResultsByRunId(runId).stream()
                .collect(Collectors.groupingBy(r -> String.valueOf(r.get("test_case_name")), Collectors.counting()));
    }

    @Test
    @DisplayName("ALL-turns-match filter includes fully-matching cases and excludes any with a failing/unknown turn")
    void allTurnsMatchFiltersRunnableCases() {
        TestSuiteResponseDto suite = createChatSuite("MT filter A", categoryEquals("A"));
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        seedStandardCases(datasetId);
        stubDeployment();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        Map<String, Long> rows = rowsPerTestCase(run.getId());
        // Included: every turn category=A (2 rows) and the matching single-turn case (1 row).
        assertThat(rows).containsOnlyKeys("mt-all-match", "single-match");
        assertThat(rows.get("mt-all-match")).isEqualTo(2L);
        assertThat(rows.get("single-match")).isEqualTo(1L);
        // Excluded: a turn with category=B, a turn missing category, and the non-matching single-turn case.
        assertThat(rows).doesNotContainKeys("mt-one-fails", "mt-missing-field", "single-nomatch");
    }

    @Test
    @DisplayName("No filter runs every valid case unchanged (single- and multi-turn alike)")
    void noFilterRunsAllValidCases() {
        TestSuiteResponseDto suite = createChatSuite("MT no filter");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        seedStandardCases(datasetId);
        stubDeployment();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        Map<String, Long> rows = rowsPerTestCase(run.getId());
        // All five cases run: three 2-turn test cases + two single-turn cases.
        assertThat(rows)
                .containsOnlyKeys("mt-all-match", "mt-one-fails", "mt-missing-field", "single-match", "single-nomatch");
        assertThat(rows.get("mt-all-match")).isEqualTo(2L);
        assertThat(rows.get("mt-one-fails")).isEqualTo(2L);
        assertThat(rows.get("mt-missing-field")).isEqualTo(2L);
        assertThat(rows.get("single-match")).isEqualTo(1L);
        assertThat(rows.get("single-nomatch")).isEqualTo(1L);
    }

    @Test
    @DisplayName("A filter that excludes every case rejects run creation with 409")
    void filterExcludingAllRejectsRunCreation() {
        TestSuiteResponseDto suite = createChatSuite("MT filter none", categoryEquals("ZZZ"));
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        seedStandardCases(datasetId);

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("A NOT CONTAIN filter runs a multi-turn case whose intermediate turns have no value (GH #141)")
    void negatedFilterTreatsMissingPerTurnValueAsMatching() {
        TestSuiteResponseDto suite = createChatSuite("MT filter nc", categoryNotContains("London"));
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        // Turns 1-2 carry no `category` at all; only the last turn has one, and it is not "London".
        createMultiTurnCase(
                datasetId,
                "mt-null-intermediate-turns",
                List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1"), Map.of("prompt", "q2", "category", "Berlin")));
        // One turn holds the excluded value, so the whole case must still be excluded.
        createMultiTurnCase(
                datasetId,
                "mt-violating-turn",
                List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1", "category", "London")));
        createSingleTurnCase(datasetId, "single-null-category", Map.of("prompt", "s0"));
        createSingleTurnCase(datasetId, "single-violating", Map.of("prompt", "s1", "category", "London"));
        stubDeployment();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        Map<String, Long> rows = rowsPerTestCase(run.getId());
        // Included: a missing value cannot contain "London", so every turn satisfies the negated predicate.
        assertThat(rows).containsOnlyKeys("mt-null-intermediate-turns", "single-null-category");
        assertThat(rows.get("mt-null-intermediate-turns")).isEqualTo(3L);
        assertThat(rows.get("single-null-category")).isEqualTo(1L);
        // Excluded: a turn whose value actually contains "London" fails the predicate.
        assertThat(rows).doesNotContainKeys("mt-violating-turn", "single-violating");
    }
}
