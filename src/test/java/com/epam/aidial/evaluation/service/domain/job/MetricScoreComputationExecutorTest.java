package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreDefinition;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreDefinitionRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.experimental.query.service.metricscore.MetricScoreComputationExecutor;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
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
    private final MetricScoreResultRepository resultRepository = mock(MetricScoreResultRepository.class);
    private final RunMetricSnapshotRepository runMetricSnapshotRepository = mock(RunMetricSnapshotRepository.class);
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor = mock(OutputSchemaFieldExtractor.class);
    private final StructuredQueryService structuredQueryService = mock(StructuredQueryService.class);

    private final MetricScoreComputationExecutor executor = new MetricScoreComputationExecutor(
            definitionRepository,
            resultRepository,
            runMetricSnapshotRepository,
            outputSchemaFieldExtractor,
            structuredQueryService,
            new JsonMapperConfiguration().objectMapper());

    private MetricScoreComputationContext context() {
        return MetricScoreComputationContext.builder()
                .testSuiteRunId(RUN_ID)
                .testSuiteId(SUITE_ID)
                .computationId(COMPUTATION_ID)
                .cancellationSignal(new AtomicBoolean(false))
                .build();
    }

    private void twoMetricFields() {
        when(runMetricSnapshotRepository.findByRunIdAndComputationId(RUN_ID, COMPUTATION_ID))
                .thenReturn(List.of(snapshot("Relevancy"), snapshot("Accuracy")));
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
                .type(MetricScoreConstants.TYPE_DEFAULT)
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
    @DisplayName("runs per-metric expressions once per field and the overall expression once at run level")
    void computesResultPerDefinitionAndMetricFieldPlusOverall() {
        twoMetricFields();
        when(definitionRepository.findApplicable(SUITE_ID))
                .thenReturn(List.of(
                        definition("AVG", AVG_EXPRESSION),
                        definition("P90", P90_EXPRESSION),
                        definition(MetricScoreConstants.SCORE_OVERALL, OVERALL_EXPRESSION)));
        when(structuredQueryService.execute(any(), any()))
                .thenAnswer(invocation -> answer(invocation.getArgument(1), 0.6, 0.8, 0.7));

        executor.execute(context());

        final List<MetricScoreResult> saved = captureSaved();
        // 2 per-metric stats x 2 metric fields + 1 run-level overall.
        assertThat(saved).hasSize(5);
        assertThat(saved)
                .filteredOn(r -> "AVG".equals(r.getMetricScoreName()))
                .extracting(MetricScoreResult::getMetricName, MetricScoreResult::getValue)
                .containsExactlyInAnyOrder(Tuple.tuple("Relevancy.score", 0.6), Tuple.tuple("Accuracy.score", 0.8));
        assertThat(saved).filteredOn(r -> "P90".equals(r.getMetricScoreName())).hasSize(2);

        final MetricScoreResult overall = saved.stream()
                .filter(r -> MetricScoreConstants.SCORE_OVERALL.equals(r.getMetricScoreName()))
                .findFirst()
                .orElseThrow();
        assertThat(overall.getMetricName()).isEqualTo(MetricScoreConstants.SCORE_OVERALL);
        assertThat(overall.getValue()).isEqualTo(0.7);
        assertThat(overall.getComputationId()).isEqualTo(COMPUTATION_ID);
    }

    @Test
    @DisplayName("binds the overall :metricAvgs param to an avg(...) array with one term per metric field")
    void bindsMetricAvgsArrayToPerMetricAverages() {
        twoMetricFields();
        when(definitionRepository.findApplicable(SUITE_ID))
                .thenReturn(List.of(definition(MetricScoreConstants.SCORE_OVERALL, OVERALL_EXPRESSION)));
        when(structuredQueryService.execute(any(), any()))
                .thenAnswer(invocation -> answer(invocation.getArgument(1), 0.6, 0.8, 0.7));

        executor.execute(context());

        final ArgumentCaptor<Map<String, Expr>> params = captureParams();
        verify(structuredQueryService).execute(any(), params.capture());
        final Expr metricAvgs = params.getValue().get(MetricScoreConstants.PARAM_METRIC_AVGS);
        assertThat(metricAvgs).isInstanceOf(ArrayExpr.class);
        assertThat(((ArrayExpr) metricAvgs).items()).hasSize(2);
    }

    @Test
    @DisplayName("isolates a failing metric field: the remaining pairs are still computed and persisted")
    void isolatesFailingMetricField() {
        twoMetricFields();
        when(definitionRepository.findApplicable(SUITE_ID)).thenReturn(List.of(definition("AVG", AVG_EXPRESSION)));
        when(structuredQueryService.execute(any(), any())).thenAnswer(invocation -> {
            final Map<String, Expr> params = invocation.getArgument(1);
            final String token = ((FieldExpr) params.get(MetricScoreConstants.PARAM_METRIC_FIELD)).name();
            if (token.contains("Accuracy")) {
                throw new ValidationException("non-numeric metric value");
            }
            return new QueryResultPage(List.of(Map.of("value", 0.6)), null);
        });

        executor.execute(context());

        final List<MetricScoreResult> saved = captureSaved();
        // The Accuracy AVG failed; only the Relevancy AVG survives.
        assertThat(saved)
                .extracting(MetricScoreResult::getMetricScoreName, MetricScoreResult::getMetricName)
                .containsExactly(Tuple.tuple("AVG", "Relevancy.score"));
    }

    @Test
    @DisplayName("skips computation and persists nothing when the run has no numeric metric fields")
    void skipsWhenNoMetricFields() {
        when(runMetricSnapshotRepository.findByRunIdAndComputationId(RUN_ID, COMPUTATION_ID))
                .thenReturn(List.of());

        executor.execute(context());

        verify(resultRepository, never()).saveAll(any());
        verify(structuredQueryService, never()).execute(any(), any());
    }

    @SuppressWarnings("unchecked")
    private List<MetricScoreResult> captureSaved() {
        final ArgumentCaptor<List<MetricScoreResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(resultRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Expr>> captureParams() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
