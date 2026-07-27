package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.AnalyticsResultService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ResultCountResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/analytics/test-case-results")
@RequiredArgsConstructor
@Tag(name = "Analytics Results", description = "Test case run result analytics endpoints")
public class AnalyticsResultController {

    private final AnalyticsResultService analyticsResultService;
    private final PaginationParamResolver paginationParamResolver;

    @PostMapping
    @Operation(summary = "Batch write test case run results")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchWriteResponseDto batchCreate(@Valid @RequestBody BatchWriteRequestDto request) {
        return analyticsResultService.batchCreate(request);
    }

    @GetMapping
    @Operation(
            summary = "List test case run results with cursor-based pagination",
            description = "Intra-run row ORDER IS NOT GUARANTEED. Keyset pagination orders by "
                    + "`(createdAtMs, id)`, and because `createdAtMs` is constant for every row of a run and "
                    + "`id` is a random UUID, the effective order within a run is arbitrary. Clients needing "
                    + "chain or turn order MUST sort by `(runIndex, requestIndex, turnIndex)`.")
    public CursorPageResponseDto<TestCaseRunResultResponseDto> list(
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter,
            @Parameter(description = "Page size") @RequestParam(required = false) Integer size,
            @Parameter(description = "Cursor for next page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Sort parameter (not supported on this endpoint)") @RequestParam(required = false)
                    String sort) {
        if (sort != null) {
            throw new ValidationException("sort is not supported on this endpoint");
        }
        int resolvedSize = paginationParamResolver.resolveSize(size);
        return analyticsResultService.listByFilter(filter, cursor, resolvedSize);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get test case run result by ID")
    public TestCaseRunResultResponseDto getById(@Parameter(description = "Result ID") @PathVariable UUID id) {
        return analyticsResultService.getById(id);
    }

    @GetMapping("/count")
    @Operation(summary = "Count test case run results matching filters")
    public ResultCountResponseDto count(
            @Parameter(description = "Filter conditions (field:operator:value)") @FilterParam List<String> filter) {
        return analyticsResultService.countByFilter(filter);
    }
}
