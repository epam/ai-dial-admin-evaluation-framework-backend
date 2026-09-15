package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateRowDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.RunCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@LogExecution
@RequiredArgsConstructor
public class CostService {

    private final AdasCostQueryBuilder adasCostQueryBuilder;
    private final DialAdasClient dialAdasClient;
    private final TestSuiteRunService testSuiteRunService;

    public RunCostsResponseDto getRunCosts(UUID runId) {
        testSuiteRunService.ensureRunExists(runId);

        Double avgTestCaseCost = fetchAvgCost(runId, TracingConstants.PHASE_EXECUTION);
        Double avgMetricEvalCost = fetchAvgCost(runId, TracingConstants.PHASE_METRIC_EVALUATION);
        return RunCostsResponseDto.builder()
                .avgTestCaseCost(avgTestCaseCost)
                .avgMetricEvalCost(avgMetricEvalCost)
                .build();
    }

    private Double fetchAvgCost(UUID runId, String phase) {
        AdasAggregateResponseDto response =
                dialAdasClient.executeAggregate(adasCostQueryBuilder.buildRunAggregateQuery(runId, phase));
        if (response == null || response.getRows() == null || response.getRows().isEmpty()) {
            return null;
        }
        AdasAggregateRowDto row = response.getRows().get(0);
        if (row.getCount() == null || row.getCount() == 0) {
            return null;
        }
        return row.getAvgCost();
    }

    public DeploymentCostsResponseDto getDeploymentCosts(String deploymentId, long fromMs, long toMs) {
        if (fromMs > toMs) {
            throw new ValidationException("from must be <= to");
        }

        Double totalTestCaseCost = fetchTotalCost(deploymentId, fromMs, toMs, TracingConstants.PHASE_EXECUTION);
        Double totalMetricEvalCost =
                fetchTotalCost(deploymentId, fromMs, toMs, TracingConstants.PHASE_METRIC_EVALUATION);
        return DeploymentCostsResponseDto.builder()
                .totalTestCaseCost(totalTestCaseCost)
                .totalMetricEvalCost(totalMetricEvalCost)
                .build();
    }

    private Double fetchTotalCost(String deploymentId, long fromMs, long toMs, String phase) {
        AdasAggregateResponseDto response = dialAdasClient.executeAggregate(
                adasCostQueryBuilder.buildDeploymentAggregateQuery(deploymentId, fromMs, toMs, phase));
        if (response == null || response.getRows() == null || response.getRows().isEmpty()) {
            return null;
        }
        AdasAggregateRowDto row = response.getRows().get(0);
        if (row.getCount() == null || row.getCount() == 0) {
            return null;
        }
        return row.getTotalCost();
    }
}
