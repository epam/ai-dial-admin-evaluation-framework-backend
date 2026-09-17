package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasBatchRunCostRowDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasDeploymentCostRowDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasRunAvgCostRowDto;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.RunCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalRunCostResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Collection;
import java.util.HashMap;
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

    public RunCostsResponseDto getRunCosts(UUID runId) {
        testSuiteRunService.ensureRunExists(runId);

        Double avgTestCaseCost = fetchAvgCost(runId, TracingConstants.PHASE_EXECUTION);
        Double avgMetricEvalCost = fetchAvgCost(runId, TracingConstants.PHASE_METRIC_EVALUATION);
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

        Map<UUID, Double> totalsByRunId = fetchTotalCosts(runIds);
        return runIds.stream()
                .map(runId -> TotalRunCostResponseDto.builder()
                        .runId(runId)
                        .totalCost(totalsByRunId.get(runId))
                        .build())
                .toList();
    }

    /**
     * Computes total cost for a batch of runs in a single dial-adas call
     * ({@link AdasCostQueryBuilder#buildPageTotalCostQuery}). dial-adas only returns a row for a run id
     * that matched at least one usage row, so a requested id with no matching row is simply absent from
     * the returned map. A {@code DialAdasClientException} from the underlying call is not caught here —
     * it propagates to the caller (surfacing as a 502/504 for the whole batch, same as every other cost
     * endpoint), since there is no partial-failure case for a single HTTP call.
     */
    private Map<UUID, Double> fetchTotalCosts(Collection<UUID> runIds) {
        AdasAggregateResponseDto<AdasBatchRunCostRowDto> response = dialAdasClient.executeAggregate(
                adasCostQueryBuilder.buildPageTotalCostQuery(runIds), AdasBatchRunCostRowDto.class);

        Map<UUID, Double> totalsByRunId = new HashMap<>();
        if (response == null || response.getRows() == null) {
            return totalsByRunId;
        }
        for (AdasBatchRunCostRowDto row : response.getRows()) {
            UUID runId = parseRunId(row.getRunId());
            if (runId != null) {
                totalsByRunId.put(runId, row.getTotalCost());
            }
        }
        return totalsByRunId;
    }

    private static UUID parseRunId(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Double fetchAvgCost(UUID runId, String phase) {
        AdasAggregateResponseDto<AdasRunAvgCostRowDto> response = dialAdasClient.executeAggregate(
                adasCostQueryBuilder.buildRunAggregateQuery(runId, phase), AdasRunAvgCostRowDto.class);
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

        Double totalTestCaseCost = fetchTotalCost(deploymentId, fromMs, toMs, TracingConstants.PHASE_EXECUTION);
        Double totalMetricEvalCost =
                fetchTotalCost(deploymentId, fromMs, toMs, TracingConstants.PHASE_METRIC_EVALUATION);
        return DeploymentCostsResponseDto.builder()
                .totalTestCaseCost(totalTestCaseCost)
                .totalMetricEvalCost(totalMetricEvalCost)
                .build();
    }

    private Double fetchTotalCost(String deploymentId, long fromMs, long toMs, String phase) {
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
