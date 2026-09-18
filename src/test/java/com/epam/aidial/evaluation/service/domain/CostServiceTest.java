package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialadas.DialAdasClientException;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasBatchRunCostRowDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasDeploymentCostRowDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasRunAvgCostRowDto;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.RunCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalRunCostResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("CostService")
@ExtendWith(MockitoExtension.class)
class CostServiceTest {

    @Mock
    private AdasCostQueryBuilder adasCostQueryBuilder;

    @Mock
    private DialAdasClient dialAdasClient;

    @Mock
    private TestSuiteRunService testSuiteRunService;

    private CostService service;

    private static AdasAggregateResponseDto<AdasDeploymentCostRowDto> aggregateResponse(long count, Double totalCost) {
        return AdasAggregateResponseDto.<AdasDeploymentCostRowDto>builder()
                .rows(List.of(AdasDeploymentCostRowDto.builder()
                        .count(count)
                        .totalCost(totalCost)
                        .build()))
                .build();
    }

    @BeforeEach
    void setUp() {
        service = new CostService(adasCostQueryBuilder, dialAdasClient, testSuiteRunService);
    }

    @Nested
    @DisplayName("getDeploymentCosts")
    class GetDeploymentCosts {

        private final String deploymentId = "applications/public/my-app";
        private final long fromMs = 1_000L;
        private final long toMs = 2_000L;

        private final StructuredQuery executionQuery =
                new StructuredQuery("execution-query", null, QueryMode.AGGREGATE, false, null, null, null, null, null);
        private final StructuredQuery metricEvalQuery = new StructuredQuery(
                "metric-eval-query", null, QueryMode.AGGREGATE, false, null, null, null, null, null);

        private void stubQueries() {
            when(adasCostQueryBuilder.buildDeploymentAggregateQuery(
                            deploymentId, fromMs, toMs, TracingConstants.PHASE_EXECUTION))
                    .thenReturn(executionQuery);
            when(adasCostQueryBuilder.buildDeploymentAggregateQuery(
                            deploymentId, fromMs, toMs, TracingConstants.PHASE_METRIC_EVALUATION))
                    .thenReturn(metricEvalQuery);
        }

        @Test
        @DisplayName("calls dial-adas once per phase and returns both totals when both phases have usage-log rows")
        void returnsBothTotalsWhenBothPhasesHaveData() {
            stubQueries();
            when(dialAdasClient.executeAggregate(executionQuery, AdasDeploymentCostRowDto.class))
                    .thenReturn(aggregateResponse(120L, 0.0007125));
            when(dialAdasClient.executeAggregate(metricEvalQuery, AdasDeploymentCostRowDto.class))
                    .thenReturn(aggregateResponse(60L, 0.000231));

            DeploymentCostsResponseDto costs = service.getDeploymentCosts(deploymentId, fromMs, toMs);

            assertThat(costs.getTotalTestCaseCost()).isEqualTo(0.0007125);
            assertThat(costs.getTotalMetricEvalCost()).isEqualTo(0.000231);
            verify(dialAdasClient).executeAggregate(executionQuery, AdasDeploymentCostRowDto.class);
            verify(dialAdasClient).executeAggregate(metricEvalQuery, AdasDeploymentCostRowDto.class);
        }

        @Test
        @DisplayName("returns null for a phase with zero matching usage-log rows")
        void returnsNullForPhaseWithZeroCount() {
            stubQueries();
            when(dialAdasClient.executeAggregate(executionQuery, AdasDeploymentCostRowDto.class))
                    .thenReturn(aggregateResponse(0L, null));
            when(dialAdasClient.executeAggregate(metricEvalQuery, AdasDeploymentCostRowDto.class))
                    .thenReturn(aggregateResponse(60L, 0.000231));

            DeploymentCostsResponseDto costs = service.getDeploymentCosts(deploymentId, fromMs, toMs);

            assertThat(costs.getTotalTestCaseCost()).isNull();
            assertThat(costs.getTotalMetricEvalCost()).isEqualTo(0.000231);
        }

        @Test
        @DisplayName("returns null for a phase with an empty rows list")
        void returnsNullForPhaseWithEmptyRows() {
            stubQueries();
            when(dialAdasClient.executeAggregate(executionQuery, AdasDeploymentCostRowDto.class))
                    .thenReturn(AdasAggregateResponseDto.<AdasDeploymentCostRowDto>builder()
                            .rows(List.of())
                            .build());
            when(dialAdasClient.executeAggregate(metricEvalQuery, AdasDeploymentCostRowDto.class))
                    .thenReturn(aggregateResponse(60L, 0.000231));

            DeploymentCostsResponseDto costs = service.getDeploymentCosts(deploymentId, fromMs, toMs);

            assertThat(costs.getTotalTestCaseCost()).isNull();
            assertThat(costs.getTotalMetricEvalCost()).isEqualTo(0.000231);
        }

        @Test
        @DisplayName("throws ValidationException when from > to")
        void throwsWhenFromAfterTo() {
            assertThatThrownBy(() -> service.getDeploymentCosts(deploymentId, toMs, fromMs))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("getRunCosts")
    class GetRunCosts {

        private final UUID runId = UUID.randomUUID();
        private final String executionSql = "execution-sql";
        private final String metricEvalSql = "metric-eval-sql";

        private void stubQueries() {
            when(adasCostQueryBuilder.buildAvgCostPerTestCaseSql(runId, TracingConstants.PHASE_EXECUTION))
                    .thenReturn(executionSql);
            when(adasCostQueryBuilder.buildAvgCostPerTestCaseSql(runId, TracingConstants.PHASE_METRIC_EVALUATION))
                    .thenReturn(metricEvalSql);
        }

        private AdasAggregateResponseDto<AdasRunAvgCostRowDto> avgAggregateResponse(long count, Double avgCost) {
            return AdasAggregateResponseDto.<AdasRunAvgCostRowDto>builder()
                    .rows(List.of(AdasRunAvgCostRowDto.builder()
                            .count(count)
                            .avgCost(avgCost)
                            .build()))
                    .build();
        }

        @Test
        @DisplayName("verifies the run exists, then returns both averages when both phases have usage-log rows")
        void returnsBothAveragesWhenBothPhasesHaveData() {
            stubQueries();
            when(dialAdasClient.executeSql(executionSql, AdasRunAvgCostRowDto.class))
                    .thenReturn(avgAggregateResponse(120L, 0.0007125));
            when(dialAdasClient.executeSql(metricEvalSql, AdasRunAvgCostRowDto.class))
                    .thenReturn(avgAggregateResponse(60L, 0.000231));

            RunCostsResponseDto costs = service.getRunCosts(runId);

            assertThat(costs.getAvgTestCaseCost()).isEqualTo(0.0007125);
            assertThat(costs.getAvgMetricEvalCost()).isEqualTo(0.000231);
            verify(testSuiteRunService).ensureRunExists(runId);
        }

        @Test
        @DisplayName("returns null for a phase with zero matching usage-log rows")
        void returnsNullForPhaseWithNoData() {
            stubQueries();
            when(dialAdasClient.executeSql(executionSql, AdasRunAvgCostRowDto.class))
                    .thenReturn(avgAggregateResponse(0L, null));
            when(dialAdasClient.executeSql(metricEvalSql, AdasRunAvgCostRowDto.class))
                    .thenReturn(avgAggregateResponse(60L, 0.000231));

            RunCostsResponseDto costs = service.getRunCosts(runId);

            assertThat(costs.getAvgTestCaseCost()).isNull();
            assertThat(costs.getAvgMetricEvalCost()).isEqualTo(0.000231);
        }

        @Test
        @DisplayName("propagates EntityNotFoundException from the run-existence check without querying dial-adas")
        void propagatesNotFoundFromExistenceCheck() {
            UUID unknownRunId = UUID.randomUUID();
            doThrow(new EntityNotFoundException("TestSuiteRun not found with id: " + unknownRunId))
                    .when(testSuiteRunService)
                    .ensureRunExists(unknownRunId);

            assertThatThrownBy(() -> service.getRunCosts(unknownRunId)).isInstanceOf(EntityNotFoundException.class);

            verify(dialAdasClient, never()).executeSql(any(), any());
        }
    }

    @Nested
    @DisplayName("getTotalRunCosts")
    class GetTotalRunCosts {

        private final UUID runId1 = UUID.randomUUID();
        private final UUID runId2 = UUID.randomUUID();

        private final StructuredQuery pageTotalCostQuery = new StructuredQuery(
                "page-total-cost-query", null, QueryMode.AGGREGATE, false, null, null, null, null, null);

        @Test
        @DisplayName("returns results in the caller's requested order, null totalCost for an unmatched run id")
        void returnsResultsInRequestedOrder() {
            when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1, runId2)))
                    .thenReturn(pageTotalCostQuery);
            when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                    .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                            .rows(List.of(AdasBatchRunCostRowDto.builder()
                                    .runId(runId1.toString())
                                    .totalCost(0.05)
                                    .build()))
                            .build());

            List<TotalRunCostResponseDto> result = service.getTotalRunCosts(List.of(runId1, runId2));

            assertThat(result)
                    .extracting(TotalRunCostResponseDto::getRunId, TotalRunCostResponseDto::getTotalCost)
                    .containsExactly(tuple(runId1, 0.05), tuple(runId2, null));
        }

        @Test
        @DisplayName("excludes the \"other\" bucket row from the result")
        void excludesOtherBucketRow() {
            when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1))).thenReturn(pageTotalCostQuery);
            when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                    .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                            .rows(List.of(AdasBatchRunCostRowDto.builder()
                                    .runId("other")
                                    .totalCost(1.5)
                                    .build()))
                            .build());

            List<TotalRunCostResponseDto> result = service.getTotalRunCosts(List.of(runId1));

            assertThat(result)
                    .extracting(TotalRunCostResponseDto::getRunId, TotalRunCostResponseDto::getTotalCost)
                    .containsExactly(tuple(runId1, null));
        }

        @Test
        @DisplayName("propagates DialAdasClientException from the underlying call")
        void propagatesDialAdasClientException() {
            when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1, runId2)))
                    .thenReturn(pageTotalCostQuery);
            when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                    .thenThrow(new DialAdasClientException(502, "boom"));

            assertThatThrownBy(() -> service.getTotalRunCosts(List.of(runId1, runId2)))
                    .isInstanceOf(DialAdasClientException.class);
        }

        @Test
        @DisplayName("throws ValidationException for empty runIds without calling dial-adas")
        void throwsForEmptyInput() {
            assertThatThrownBy(() -> service.getTotalRunCosts(List.<UUID>of())).isInstanceOf(ValidationException.class);

            verifyNoInteractions(dialAdasClient);
        }

        @Test
        @DisplayName("throws ValidationException when runIds exceeds MAX_BATCH_RUN_IDS without calling dial-adas")
        void throwsForOverLimitInput() {
            List<UUID> tooMany = IntStream.range(0, ValidationConstants.MAX_BATCH_RUN_IDS + 1)
                    .mapToObj(i -> UUID.randomUUID())
                    .collect(Collectors.toList());

            assertThatThrownBy(() -> service.getTotalRunCosts(tooMany)).isInstanceOf(ValidationException.class);

            verifyNoInteractions(dialAdasClient);
        }
    }
}
