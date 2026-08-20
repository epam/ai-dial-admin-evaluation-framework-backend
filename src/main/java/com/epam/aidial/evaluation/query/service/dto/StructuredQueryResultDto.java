package com.epam.aidial.evaluation.query.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Result of executing a structured query. {@code rows} are ordered field-name → value maps (keys are
 * the requested field names or aggregate aliases); JSONB-backed columns are returned as nested JSON,
 * not escaped strings. {@code totalCount} is present only when offset paging requested
 * {@code include_total} in row mode.
 */
@Schema(description = "Rows produced by a structured query, plus an optional total row count.")
public record StructuredQueryResultDto(
        @Schema(
                description = "Projected rows as field-name → value maps, in field order.",
                example = "[{\"id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\",\"name\":\"my-suite\"}]")
        List<Map<String, Object>> rows,

        @Schema(
                description = "Total matching rows ignoring paging; only set for row-mode offset paging"
                        + " with include_total=true.",
                example = "42",
                nullable = true)
        Long totalCount) {}
