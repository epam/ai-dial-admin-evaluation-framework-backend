package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.data.db.model.MetricScoreDefinition;
import com.epam.aidial.evaluation.data.db.model.MetricScoreDefinitionType;
import com.epam.aidial.evaluation.data.db.repository.MetricScoreDefinitionRepository;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
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

    private static final String AVG_EXPRESSION = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\","
            + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"avg\","
            + "\"args\":[{\"type\":\"param\",\"name\":\"metricField\"}]},\"as\":\"value\"}]}";
    private static final String P90_EXPRESSION = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\","
            + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"percentile_cont\","
            + "\"args\":[{\"type\":\"value\",\"value_type\":\"decimal\",\"value\":\"0.9\"},"
            + "{\"type\":\"param\",\"name\":\"metricField\"}]},\"as\":\"value\"}]}";
    private static final String OVERALL_EXPRESSION = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\","
            + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"mean\","
            + "\"args\":[{\"type\":\"param\",\"name\":\"metricAvgs\"}]},\"as\":\"value\"}]}";

    private final MetricScoreDefinitionRepository definitionRepository = mock(MetricScoreDefinitionRepository.class);
    private final RunMetricSnapshotRepository runMetricSnapshotRepository = mock(RunMetricSnapshotRepository.class);
    private final MetricScoreService metricScoreService = mock(MetricScoreService.class);
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor = mock(OutputSchemaFieldExtractor.class);
    private final StructuredQueryService structuredQueryService = mock(StructuredQueryService.class);

    private final MetricScoreComputationExecutor executor = new MetricScoreComputationExecutor(
            definitionRepository,
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

    private static MetricScoreDefinition definition(String name, String expression) {
        return MetricScoreDefinition.builder()
                .id(UUID.randomUUID())
                .type(MetricScoreDefinitionType.DEFAULT)
                .name(name)
                .expression(expression)
                .build();
    }

    /**
     * Per-metric expressions bind {@code :metricField}; the run-level {@code overall} binds the
     * {@code :metricAvgs} array. Returns 0.6 for the Relevancy field, 0.8 for Accuracy, and the
     * supplied run-level value for {@code overall}.
     */
    private static QueryResultPage answer(Map<String, Expr> params, double relevancy, double accuracy, double overall) {
        if (params.containsKey(MetricScoreConstants.PARAM_METRIC_AVGS)) {
            return new QueryResultPage(List.of(Map.of("value", overall)), null);
        }
        final String token = ((FieldExpr) params.get(MetricScoreConstants.PARAM_METRIC_FIELD)).name();
        return new QueryResultPage(List.of(Map.of("value", token.contains("Relevancy") ? relevancy : accuracy)), null);
    }

    @Test
    @DisplayName("computes per-metric stats and the default overall when the run has a single metric field")
    void computesPerMetricStatsAndDefaultOverallForSingleMetric() {
        oneMetricField();
        when(definitionRepository.findAll())
                .thenReturn(List.of(definition("AVG", AVG_EXPRESSION), definition("P90", P90_EXPRESSION)));
        when(structuredQueryService.execute(any(), any()))
                .thenAnswer(invocation -> answer(invocation.getArgument(1), 0.6, 0.8, 0.7));

        executor.execute(context(null));

        final List<MetricScoreResult> saved = captureSaved();
        // AVG + P90 over the single field, plus the default overall (single-metric → computed).
        assertThat(saved).hasSize(3);
        assertThat(saved)
                .filteredOn(r -> "AVG".equals(r.getMetricScoreName()))
                .extracting(MetricScoreResult::getMetricName, MetricScoreResult::getValue)
                .containsExactly(Tuple.tuple("Relevancy.score", 0.6));
        final MetricScoreResult overall = saved.stream()
                .filter(r -> MetricScoreConstants.SCORE_OVERALL.equals(r.getMetricScoreName()))
                .findFirst()
                .orElseThrow();
        assertThat(overall.getMetricName()).isEqualTo(MetricScoreConstants.SCORE_OVERALL);
        assertThat(overall.getValue()).isEqualTo(0.7);
        assertThat(overall.getComputationId()).isEqualTo(COMPUTATION_ID);
    }

    @Test
    @DisplayName("skips the default overall when the run has more than one metric field")
    void skipsDefaultOverallForMultipleMetrics() {
        twoMetricFields();
        when(definitionRepository.findAll()).thenReturn(List.of(definition("AVG", AVG_EXPRESSION)));
        when(structuredQueryService.execute(any(), any()))
                .thenAnswer(invocation -> answer(invocation.getArgument(1), 0.6, 0.8, 0.7));

        executor.execute(context(null));

        final List<MetricScoreResult> saved = captureSaved();
        // AVG over both fields; NO overall row (default overall is single-metric only).
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(MetricScoreResult::getMetricScoreName).containsOnly("AVG");
    }

    @Test
    @DisplayName("computes a custom overall over the avg(...) array even with multiple metric fields")
    void computesCustomOverallForMultipleMetrics() {
        twoMetricFields();
        when(definitionRepository.findAll()).thenReturn(List.of(definition("AVG", AVG_EXPRESSION)));
        when(structuredQueryService.execute(any(), any()))
                .thenAnswer(invocation -> answer(invocation.getArgument(1), 0.6, 0.8, 0.7));

        executor.execute(context(OVERALL_EXPRESSION));

        final List<MetricScoreResult> saved = captureSaved();
        final MetricScoreResult overall = saved.stream()
                .filter(r -> MetricScoreConstants.SCORE_OVERALL.equals(r.getMetricScoreName()))
                .findFirst()
                .orElseThrow();
        assertThat(overall.getValue()).isEqualTo(0.7);

        // The overall invocation binds :metricAvgs to one avg(...) term per metric field.
        final ArgumentCaptor<Map<String, Expr>> params = captureParams();
        verify(structuredQueryService, atLeastOnce()).execute(any(), params.capture());
        final ArrayExpr metricAvgs = params.getAllValues().stream()
                .filter(p -> p.containsKey(MetricScoreConstants.PARAM_METRIC_AVGS))
                .map(p -> (ArrayExpr) p.get(MetricScoreConstants.PARAM_METRIC_AVGS))
                .findFirst()
                .orElseThrow();
        assertThat(metricAvgs.items()).hasSize(2);
    }

    @Test
    @DisplayName("isolates a failing metric field: the remaining pairs are still computed and persisted")
    void isolatesFailingMetricField() {
        twoMetricFields();
        when(definitionRepository.findAll()).thenReturn(List.of(definition("AVG", AVG_EXPRESSION)));
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
        // The Accuracy AVG failed; the default overall is skipped (two metrics) — only Relevancy AVG survives.
        assertThat(saved)
                .extracting(MetricScoreResult::getMetricScoreName, MetricScoreResult::getMetricName)
                .containsExactly(Tuple.tuple("AVG", "Relevancy.score"));
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

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Expr>> captureParams() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
