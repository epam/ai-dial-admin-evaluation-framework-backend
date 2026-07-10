package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.experimental.query.service.metricscore.BuiltInMetricStatistics;
import com.epam.aidial.evaluation.experimental.query.service.metricscore.MetricScoreComputationExecutor;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.analytics.MetricScoreService;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MetricScoreComputationExecutorTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUITE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMPUTATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** The built-in per-metric statistic names (AVG/P10/P90/MIN/MAX), all run per metric field. */
    private static final List<String> PER_METRIC_NAMES = List.of("AVG", "P10", "P90", "MIN", "MAX");

    private static final long FIXED_MILLIS = 1_700_000_000_000L;

    /**
     * A custom (non-default) overall expression in JSON, exercising the snapshot-driven parse path. It is
     * self-contained — it references a real metric column directly and binds no executor placeholders.
     */
    private static final String CUSTOM_OVERALL_EXPRESSION = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\","
            + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"avg\","
            + "\"args\":[{\"type\":\"field\",\"name\":\"metric:Relevancy:score\"}]},\"as\":\"value\"}]}";

    private final RunMetricSnapshotRepository runMetricSnapshotRepository = mock(RunMetricSnapshotRepository.class);
    private final MetricScoreService metricScoreService = mock(MetricScoreService.class);
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor = mock(OutputSchemaFieldExtractor.class);
    private final StructuredQueryService structuredQueryService = mock(StructuredQueryService.class);

    private final MetricScoreComputationExecutor executor = new MetricScoreComputationExecutor(
            new BuiltInMetricStatistics(),
            runMetricSnapshotRepository,
            metricScoreService,
            outputSchemaFieldExtractor,
            structuredQueryService,
            new JsonMapperConfiguration().objectMapper());

    private MetricScoreComputationContext context(String overallExpression) {
        return MetricScoreComputationContext.builder()
                .testSuiteRunId(RUN_ID)
                .testSuiteId(SUITE_ID)
                .computationId(COMPUTATION_ID)
                .overallExpression(overallExpression)
                .computedAtMs(FIXED_MILLIS)
                .cancellationSignal(new AtomicBoolean(false))
                .build();
    }

    private void twoMetricFields() {
        when(runMetricSnapshotRepository.findByRunIdAndComputationId(RUN_ID, COMPUTATION_ID))
                .thenReturn(List.of(snapshot("Relevancy"), snapshot("Accuracy")));
        when(outputSchemaFieldExtractor.extractFieldNames(any())).thenReturn(List.of("score"));
    }

    private void oneMetricField() {
        when(runMetricSnapshotRepository.findByRunIdAndComputationId(RUN_ID, COMPUTATION_ID))
                .thenReturn(List.of(snapshot("Relevancy")));
        when(outputSchemaFieldExtractor.extractFieldNames(any())).thenReturn(List.of("score"));
    }

    private static RunMetricSnapshot snapshot(String tsmdName) {
        return RunMetricSnapshot.builder()
                .tsmdName(tsmdName)
                .outputSchema("{\"properties\":{\"score\":{}}}")
                .build();
    }

    /**
     * Per-metric stats and the default overall bind {@code :metricField} (the default overall to the
     * single field); a custom overall is self-contained and binds neither. Returns 0.6 for the Relevancy
     * field, 0.8 for Accuracy, and the supplied run-level value for a custom (no-field) overall.
     */
    private static QueryResultPage answer(Map<String, Expr> params, double relevancy, double accuracy, double custom) {
        final Expr metricField = params.get(MetricScoreConstants.PARAM_METRIC_FIELD);
        if (metricField == null) {
            return new QueryResultPage(List.of(Map.of("value", custom)), null);
        }
        final String token = ((FieldExpr) metricField).name();
        return new QueryResultPage(List.of(Map.of("value", token.contains("Relevancy") ? relevancy : accuracy)), null);
    }

    @Test
    @DisplayName("computes the 5 built-in stats and the default overall when the run has a single metric field")
    void computesPerMetricStatsAndDefaultOverallForSingleMetric() {
        oneMetricField();
        when(structuredQueryService.execute(any(), any()))
                .thenAnswer(invocation -> answer(invocation.getArgument(1), 0.6, 0.8, 0.7));

        executor.execute(context(null));

        final List<MetricScoreResult> saved = captureSaved();
        // 5 built-in stats over the single field, plus the default overall (single-metric → computed).
        assertThat(saved).hasSize(6);
        // Every result carries the run's suite and the fixed compute timestamp (shared across the computation).
        assertThat(saved).allSatisfy(r -> {
            assertThat(r.getTestSuiteId()).isEqualTo(SUITE_ID);
            assertThat(r.getComputedAtMs()).isEqualTo(FIXED_MILLIS);
        });
        assertThat(saved)
                .filteredOn(r -> PER_METRIC_NAMES.contains(r.getMetricScoreName()))
                .extracting(
                        MetricScoreResult::getMetricScoreName,
                        MetricScoreResult::getMetricName,
                        MetricScoreResult::getValue)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("AVG", "Relevancy.score", 0.6),
                        Tuple.tuple("P10", "Relevancy.score", 0.6),
                        Tuple.tuple("P90", "Relevancy.score", 0.6),
                        Tuple.tuple("MIN", "Relevancy.score", 0.6),
                        Tuple.tuple("MAX", "Relevancy.score", 0.6));
        final MetricScoreResult overall = saved.stream()
                .filter(r -> MetricScoreConstants.SCORE_OVERALL.equals(r.getMetricScoreName()))
                .findFirst()
                .orElseThrow();
        assertThat(overall.getMetricName()).isEqualTo(MetricScoreConstants.SCORE_OVERALL);
        // Default overall is the single metric's average (binds :metricField to the one field).
        assertThat(overall.getValue()).isEqualTo(0.6);
        assertThat(overall.getComputationId()).isEqualTo(COMPUTATION_ID);
    }

    @Test
    @DisplayName("skips the default overall when the run has more than one metric field")
    void skipsDefaultOverallForMultipleMetrics() {
        twoMetricFields();
        when(structuredQueryService.execute(any(), any()))
                .thenAnswer(invocation -> answer(invocation.getArgument(1), 0.6, 0.8, 0.7));

        executor.execute(context(null));

        final List<MetricScoreResult> saved = captureSaved();
        // 5 built-in stats over both fields; NO overall row (default overall is single-metric only).
        assertThat(saved).hasSize(10);
        assertThat(saved).extracting(MetricScoreResult::getMetricScoreName).isSubsetOf(PER_METRIC_NAMES);
    }

    @Test
    @DisplayName("computes a self-contained custom overall (real metric columns) even with multiple metric fields")
    void computesCustomOverallForMultipleMetrics() {
        twoMetricFields();
        when(structuredQueryService.execute(any(), any()))
                .thenAnswer(invocation -> answer(invocation.getArgument(1), 0.6, 0.8, 0.7));

        executor.execute(context(CUSTOM_OVERALL_EXPRESSION));

        final List<MetricScoreResult> saved = captureSaved();
        // 5 built-in stats x 2 fields + the custom overall (run for any metric count).
        assertThat(saved).hasSize(11);
        final MetricScoreResult overall = saved.stream()
                .filter(r -> MetricScoreConstants.SCORE_OVERALL.equals(r.getMetricScoreName()))
                .findFirst()
                .orElseThrow();
        // The custom expression is self-contained — it binds no :metricField, so the executor runs it
        // with only the run-scoping params and the mock returns the custom value.
        assertThat(overall.getValue()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("isolates a failing metric field: the other field's stats are still computed and persisted")
    void isolatesFailingMetricField() {
        twoMetricFields();
        when(structuredQueryService.execute(any(), any())).thenAnswer(invocation -> {
            final Map<String, Expr> params = invocation.getArgument(1);
            final String token = ((FieldExpr) params.get(MetricScoreConstants.PARAM_METRIC_FIELD)).name();
            if (token.contains("Accuracy")) {
                throw new ValidationException("non-numeric metric value");
            }
            return new QueryResultPage(List.of(Map.of("value", 0.6)), null);
        });

        executor.execute(context(null));

        final List<MetricScoreResult> saved = captureSaved();
        // Every Accuracy stat failed; the default overall is skipped (two metrics) — only the
        // 5 Relevancy stats survive.
        assertThat(saved).hasSize(5);
        assertThat(saved).extracting(MetricScoreResult::getMetricName).containsOnly("Relevancy.score");
        assertThat(saved).extracting(MetricScoreResult::getMetricScoreName).isSubsetOf(PER_METRIC_NAMES);
    }

    @Test
    @DisplayName("skips computation and persists nothing when the run has no numeric metric fields")
    void skipsWhenNoMetricFields() {
        when(runMetricSnapshotRepository.findByRunIdAndComputationId(RUN_ID, COMPUTATION_ID))
                .thenReturn(List.of());

        executor.execute(context(null));

        verify(metricScoreService, never()).saveAll(any());
        verify(structuredQueryService, never()).execute(any(), any());
    }

    @SuppressWarnings("unchecked")
    private List<MetricScoreResult> captureSaved() {
        final ArgumentCaptor<List<MetricScoreResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(metricScoreService).saveAll(captor.capture());
        return captor.getValue();
    }
}
