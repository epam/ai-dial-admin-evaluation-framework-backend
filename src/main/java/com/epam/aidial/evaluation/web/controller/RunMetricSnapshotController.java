package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.RunMetricSnapshotService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotResponseDto;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/analytics/run-metric-snapshots")
@RequiredArgsConstructor
@Tag(name = "Run Metric Snapshots", description = "Metric binding snapshot endpoints")
public class RunMetricSnapshotController {

    private final RunMetricSnapshotService snapshotService;

    @PostMapping
    @Operation(summary = "Batch write run metric snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchWriteResponseDto batchCreate(@Valid @RequestBody RunMetricSnapshotBatchWriteRequestDto request) {
        return snapshotService.batchCreate(request);
    }

    @GetMapping
    @Operation(summary = "List run metric snapshots by run ID")
    public List<RunMetricSnapshotResponseDto> list(
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter) {
        return snapshotService.listByFilter(filter);
    }
}
