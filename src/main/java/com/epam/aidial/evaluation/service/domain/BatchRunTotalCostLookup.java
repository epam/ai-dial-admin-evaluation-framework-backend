package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasBatchRunCostRowDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reusable one-call batch total-cost lookup for {@code dial_usage_log}, shared by
 * {@link CostService#getTotalRunCosts} (normal shared {@link DialAdasClient}) and the
 * {@code TotalCostTestSuiteRunsPageExtender} (a dedicated short-timeout client). Calls
 * {@link AdasCostQueryBuilder#buildPageTotalCostQuery} exactly once and owns all response parsing: a null
 * response or null rows yield an empty map; {@link AdasBatchRunCostRowDto#getRunId()} is parsed as a
 * {@link UUID}; invalid values — including the builder's {@code "other"} bucket — are ignored; a null
 * total does not become a row key. dial-adas only returns a row for a run id that matched at least one
 * usage row, so a requested id with no matching row is simply absent from the returned map. A
 * {@code DialAdasClientException} from the underlying call is not caught here — it propagates to the
 * caller, since there is no partial-failure case for a single HTTP call.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class BatchRunTotalCostLookup {

    private final AdasCostQueryBuilder adasCostQueryBuilder;

    public Map<UUID, Double> fetchTotalCosts(Collection<UUID> runIds, DialAdasClient dialAdasClient) {
        AdasAggregateResponseDto<AdasBatchRunCostRowDto> response = dialAdasClient.executeAggregate(
                adasCostQueryBuilder.buildPageTotalCostQuery(runIds), AdasBatchRunCostRowDto.class);

        Map<UUID, Double> totalsByRunId = new HashMap<>();
        if (response == null || response.getRows() == null) {
            return totalsByRunId;
        }
        for (AdasBatchRunCostRowDto row : response.getRows()) {
            UUID runId = parseRunId(row.getRunId());
            if (runId != null && row.getTotalCost() != null) {
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
}
