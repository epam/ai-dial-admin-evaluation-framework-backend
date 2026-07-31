package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.RunMetricSnapshotService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteRequestDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service-layer client wrapper that converts internal RunMetricSnapshot items to batch write DTOs
 * and delegates to {@link RunMetricSnapshotService#batchCreate(RunMetricSnapshotBatchWriteRequestDto)}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class RunMetricSnapshotBatchWriteClient {

    private final RunMetricSnapshotService runMetricSnapshotService;

    /**
     * Writes run metric snapshot items.
     *
     * @param testSuiteRunId test suite run ID for the envelope
     * @param computationId  computation ID for the envelope
     * @param computedAtMs   computation timestamp for the envelope
     * @param snapshots      list of snapshot items to write
     */
    public void batchWrite(
            UUID testSuiteRunId,
            UUID computationId,
            Long computedAtMs,
            List<RunMetricSnapshotBatchWriteItemDto> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }

        log.debug("Writing {} run metric snapshots for run {}", snapshots.size(), testSuiteRunId);

        RunMetricSnapshotBatchWriteRequestDto request = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(computedAtMs)
                .snapshots(snapshots)
                .build();

        runMetricSnapshotService.batchCreate(request);
        log.debug("Wrote {} run metric snapshots for run {}", snapshots.size(), testSuiteRunId);
    }
}
