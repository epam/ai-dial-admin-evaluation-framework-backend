package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.properties.analytics.EvalSummaryProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteRequestDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service-layer client wrapper that converts internal EvalSummary items to batch write DTOs
 * and delegates to {@link EvalSummaryService#batchCreate(EvalSummaryBatchWriteRequestDto)}.
 * Respects the existing batch max-items limit via chunking.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class EvalSummaryBatchWriteClient {

    private final EvalSummaryService evalSummaryService;
    private final EvalSummaryProperties evalSummaryProperties;

    /**
     * Writes eval summary items in chunks, respecting the configured max-items limit.
     *
     * @param testSuiteId    test suite ID for the envelope
     * @param testSuiteRunId test suite run ID for the envelope
     * @param computationId  computation ID for the envelope
     * @param computedAtMs   computation timestamp for the envelope
     * @param items          list of eval summary items to write
     */
    public void batchWrite(
            UUID testSuiteId,
            UUID testSuiteRunId,
            UUID computationId,
            Long computedAtMs,
            List<EvalSummaryBatchWriteItemDto> items) {
        if (items.isEmpty()) {
            return;
        }

        int maxItems = evalSummaryProperties.getBatch().getMaxItems();
        int totalItems = items.size();
        log.debug("Writing {} eval summaries for run {} (max batch size: {})", totalItems, testSuiteRunId, maxItems);

        for (int offset = 0; offset < totalItems; offset += maxItems) {
            int end = Math.min(offset + maxItems, totalItems);
            List<EvalSummaryBatchWriteItemDto> chunk = items.subList(offset, end);

            EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                    .testSuiteId(testSuiteId)
                    .testSuiteRunId(testSuiteRunId)
                    .computationId(computationId)
                    .computedAtMs(computedAtMs)
                    .items(chunk)
                    .build();

            evalSummaryService.batchCreate(request);
            log.debug("Wrote eval summary chunk [{}-{}] of {} for run {}", offset, end, totalItems, testSuiteRunId);
        }
    }
}
