package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseMetricScoreAggregated;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseMetricScoreAggregatedRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("PostgresTestCaseMetricScoreAggregatedRepository tests")
public abstract class PostgresTestCaseMetricScoreAggregatedRepositoryFunctionalTests extends BaseFunctionalTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private TestCaseMetricScoreAggregatedRepository repository;

    @Test
    @DisplayName("saveAll inserts a new row; findByRunIdAndComputationId retrieves it")
    void saveAllInsertsAndFindByRunIdAndComputationIdRetrieves() {
        UUID runId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        repository.saveAll(
                List.of(buildAggregate(runId, testCaseId, computationId, "{\"MetricA\":{\"avg\":0.5}}", 1000L)));

        List<TestCaseMetricScoreAggregated> found = repository.findByRunIdAndComputationId(runId, computationId);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTestCaseId()).isEqualTo(testCaseId);
        assertJsonEquals("{\"MetricA\":{\"avg\":0.5}}", found.get(0).getMetricScores());
        assertThat(found.get(0).getCreatedAtMs()).isEqualTo(1000L);
        assertThat(found.get(0).getComputedAtMs()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("saveAll upserts metric_scores/computed_at_ms on conflict, leaving created_at_ms unchanged")
    void saveAllUpsertsOnConflictWithoutChangingCreatedAtMs() {
        UUID runId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        repository.saveAll(
                List.of(buildAggregate(runId, testCaseId, computationId, "{\"MetricA\":{\"avg\":0.5}}", 1000L)));
        repository.saveAll(
                List.of(buildAggregate(runId, testCaseId, computationId, "{\"MetricA\":{\"avg\":0.9}}", 2000L)));

        List<TestCaseMetricScoreAggregated> found = repository.findByRunIdAndComputationId(runId, computationId);

        assertThat(found).hasSize(1);
        assertJsonEquals("{\"MetricA\":{\"avg\":0.9}}", found.get(0).getMetricScores());
        assertThat(found.get(0).getComputedAtMs()).isEqualTo(2000L);
        assertThat(found.get(0).getCreatedAtMs()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("findByRunIdAndComputationId returns only rows for the given run and computation")
    void findByRunIdAndComputationIdScopesToRunAndComputation() {
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID otherComputationId = UUID.randomUUID();

        repository.saveAll(List.of(
                buildAggregate(runId, UUID.randomUUID(), computationId, "{}", 1000L),
                buildAggregate(runId, UUID.randomUUID(), otherComputationId, "{}", 1000L),
                buildAggregate(otherRunId, UUID.randomUUID(), computationId, "{}", 1000L)));

        List<TestCaseMetricScoreAggregated> found = repository.findByRunIdAndComputationId(runId, computationId);

        assertThat(found).hasSize(1);
    }

    /**
     * Compares two JSON strings structurally rather than byte-for-byte, since Postgres reformats a
     * JSONB column's whitespace (e.g. a space after every {@code :} and {@code ,}) on write.
     */
    private static void assertJsonEquals(String expectedJson, String actualJson) {
        JsonNode expected = readJson(expectedJson);
        JsonNode actual = readJson(actualJson);
        assertThat(actual).isEqualTo(expected);
    }

    private static JsonNode readJson(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in test fixture: " + json, e);
        }
    }

    private static TestCaseMetricScoreAggregated buildAggregate(
            UUID runId, UUID testCaseId, UUID computationId, String metricScoresJson, long timestampMs) {
        return TestCaseMetricScoreAggregated.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .computationId(computationId)
                .metricScores(metricScoresJson)
                .createdAtMs(timestampMs)
                .computedAtMs(timestampMs)
                .build();
    }
}
