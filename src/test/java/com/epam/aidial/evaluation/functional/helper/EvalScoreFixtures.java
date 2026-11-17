package com.epam.aidial.evaluation.functional.helper;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Builds {@link TestCaseEvalScore} rows for functional tests, shared so test classes do not each redefine it. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EvalScoreFixtures {

    /** A SUCCESS row that passed. */
    public static TestCaseEvalScore scoreRow(
            UUID runId, UUID testCaseId, String testCaseName, UUID computationId, double score, long computedAtMs) {
        return scoreRow(
                runId, testCaseId, testCaseName, computationId, score, computedAtMs, ExecutionStatus.SUCCESS, true);
    }

    public static TestCaseEvalScore scoreRow(
            UUID runId,
            UUID testCaseId,
            String testCaseName,
            UUID computationId,
            Double score,
            long computedAtMs,
            ExecutionStatus status,
            Boolean passed) {
        return TestCaseEvalScore.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .testCaseName(testCaseName)
                .computationId(computationId)
                .executionStatus(status)
                .score(score)
                .passed(passed)
                .computedAtMs(computedAtMs)
                .build();
    }

    /** A test case's row for one run and computation, with a fresh test case id. */
    public static TestCaseEvalScore row(UUID runId, UUID computationId, ExecutionStatus status, Boolean passed) {
        final boolean scored = status == ExecutionStatus.SUCCESS && passed != null;
        return scoreRow(
                runId, UUID.randomUUID(), "case", computationId, scored ? 0.5 : null, 1_000L, status, passed);
    }
}
