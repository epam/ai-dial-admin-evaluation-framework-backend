package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasDeploymentCostRowDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasRunAvgCostRowDto;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.RunCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalRunCostResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
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
    private final BatchRunTotalCostLookup batchRunTotalCostLookup;

    public RunCostsResponseDto getRunCosts(UUID runId) {
        testSuiteRunService.ensureRunExists(runId);

        Double avgTestCaseCost = fetchAvgCost(runId, EvalPhase.EXECUTION);
        Double avgMetricEvalCost = fetchAvgCost(runId, EvalPhase.METRIC_EVALUATION);
        return RunCostsResponseDto.builder()
                .avgTestCaseCost(avgTestCaseCost)
                .avgMetricEvalCost(avgMetricEvalCost)
                .build();
    }

    public List<TotalRunCostResponseDto> getTotalRunCosts(List<UUID> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            throw new ValidationException("runIds must not be empty");
        }
        if (runIds.size() > ValidationConstants.MAX_BATCH_RUN_IDS) {
            throw new ValidationException(
                    "runIds must not exceed " + ValidationConstants.MAX_BATCH_RUN_IDS + " entries");
        }

        Map<UUID, Double> totalsByRunId = batchRunTotalCostLookup.fetchTotalCosts(runIds, dialAdasClient);
        return runIds.stream()
                .map(runId -> TotalRunCostResponseDto.builder()
                        .runId(runId)
                        .totalCost(totalsByRunId.get(runId))
                        .build())
                .toList();
    }

    private Double fetchAvgCost(UUID runId, EvalPhase phase) {
        AdasAggregateResponseDto<AdasRunAvgCostRowDto> response = dialAdasClient.executeSql(
                adasCostQueryBuilder.buildAvgCostPerTestCaseSql(runId, phase), AdasRunAvgCostRowDto.class);
        if (response == null || response.getRows() == null || response.getRows().isEmpty()) {
            return null;
        }
        AdasRunAvgCostRowDto row = response.getRows().get(0);
        if (row.getCount() == null || row.getCount() == 0) {
            return null;
        }
        return row.getAvgCost();
    }

    public DeploymentCostsResponseDto getDeploymentCosts(String deploymentId, long fromMs, long toMs) {
        if (fromMs > toMs) {
            throw new ValidationException("from must be <= to");
        }

        Double totalTestCaseCost = fetchTotalCost(deploymentId, fromMs, toMs, EvalPhase.EXECUTION);
        Double totalMetricEvalCost = fetchTotalCost(deploymentId, fromMs, toMs, EvalPhase.METRIC_EVALUATION);
        return DeploymentCostsResponseDto.builder()
                .totalTestCaseCost(totalTestCaseCost)
                .totalMetricEvalCost(totalMetricEvalCost)
                .build();
    }

    private Double fetchTotalCost(String deploymentId, long fromMs, long toMs, EvalPhase phase) {
        AdasAggregateResponseDto<AdasDeploymentCostRowDto> response = dialAdasClient.executeAggregate(
                adasCostQueryBuilder.buildDeploymentAggregateQuery(deploymentId, fromMs, toMs, phase),
                AdasDeploymentCostRowDto.class);
        if (response == null || response.getRows() == null || response.getRows().isEmpty()) {
            return null;
        }
        AdasDeploymentCostRowDto row = response.getRows().get(0);
        if (row.getCount() == null || row.getCount() == 0) {
            return null;
        }
        return row.getTotalCost();
    }
}
