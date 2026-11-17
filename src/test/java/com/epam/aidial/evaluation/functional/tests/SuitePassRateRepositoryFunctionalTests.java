package com.epam.aidial.evaluation.functional.tests;

import static com.epam.aidial.evaluation.functional.helper.EvalScoreFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunDeletionRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The analytics count read behind the suite pass-rate summary: per-run counts of {@code test_case_eval_scores}
 * rows ({@code TestCaseEvalScoreRepository.countByRunAndComputation}). The run selection lives in the meta
 * datasource ({@code TestSuiteRunRepository.findLatestCompletedRunIds}).
 */
@DisplayName("Suite pass-rate repository reads")
public abstract class SuitePassRateRepositoryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private TestCaseEvalScoreRepository scoreRepository;

    @Autowired
    private RunDeletionRepository runDeletionRepository;

    @Test
    @DisplayName("countByRunAndComputation tallies passed, not passed, unscored and failed rows of the given computation")
    void countsSplitIntoFourBuckets() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        scoreRepository.saveAll(List.of(
                row(runId, computationId, ExecutionStatus.SUCCESS, true),
                row(runId, computationId, ExecutionStatus.SUCCESS, true),
                row(runId, computationId, ExecutionStatus.SUCCESS, false),
                row(runId, computationId, ExecutionStatus.SUCCESS, null),
                row(runId, computationId, ExecutionStatus.FAILED, null)));

        Map<UUID, RunPassCounts> counts = scoreRepository.countByRunAndComputation(Map.of(runId, computationId));

        assertThat(counts).containsOnlyKeys(runId);
        assertThat(counts.get(runId)).isEqualTo(new RunPassCounts(runId, 2, 1, 1, 1));
    }

    @Test
    @DisplayName("countByRunAndComputation ignores rows of other computations of the same run")
    void countsOnlyTheRequestedComputation() {
        UUID runId = UUID.randomUUID();
        UUID wanted = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        scoreRepository.saveAll(List.of(
                row(runId, wanted, ExecutionStatus.SUCCESS, true),
                row(runId, other, ExecutionStatus.SUCCESS, false),
                row(runId, other, ExecutionStatus.FAILED, null)));

        RunPassCounts counts =
                scoreRepository.countByRunAndComputation(Map.of(runId, wanted)).get(runId);

        assertThat(counts).isEqualTo(new RunPassCounts(runId, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("countByRunAndComputation counts each run against its own computation in one call")
    void countsSeveralRunsAtOnce() {
        UUID firstSuiteRun = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        UUID computationA = UUID.randomUUID();
        UUID computationB = UUID.randomUUID();
        scoreRepository.saveAll(List.of(
                row(firstSuiteRun, computationA, ExecutionStatus.SUCCESS, true),
                row(runB, computationB, ExecutionStatus.FAILED, null),
                row(runB, computationA, ExecutionStatus.SUCCESS, true)));

        Map<UUID, RunPassCounts> counts =
                scoreRepository.countByRunAndComputation(Map.of(firstSuiteRun, computationA, runB, computationB));

        assertThat(counts.get(firstSuiteRun)).isEqualTo(new RunPassCounts(firstSuiteRun, 1, 0, 0, 0));
        assertThat(counts.get(runB)).isEqualTo(new RunPassCounts(runB, 0, 0, 0, 1));
    }

    @Test
    @DisplayName("countByRunAndComputation omits a run that has no rows for its computation")
    void runWithoutRowsIsAbsent() {
        UUID runId = UUID.randomUUID();

        assertThat(scoreRepository.countByRunAndComputation(Map.of(runId, UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    @DisplayName("countByRunAndComputation returns an empty map for an empty or null input")
    void emptyInputReturnsEmptyMap() {
        assertThat(scoreRepository.countByRunAndComputation(Map.of())).isEmpty();
        assertThat(scoreRepository.countByRunAndComputation(null)).isEmpty();
    }

    @Test
    @DisplayName("countByRunAndComputation excludes a soft-deleted run")
    void deletedRunIsNotCounted() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        scoreRepository.saveAll(List.of(row(runId, computationId, ExecutionStatus.SUCCESS, true)));
        runDeletionRepository.insert(runId, 1_000L);

        assertThat(scoreRepository.countByRunAndComputation(Map.of(runId, computationId)))
                .isEmpty();
    }
}
