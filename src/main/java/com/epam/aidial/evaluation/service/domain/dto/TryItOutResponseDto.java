package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TryItOutResponseDto {

    /** The last executed turn's resolved request; the only turn for a single-turn invocation. */
    private ResolvedRequestDto resolvedRequest;

    /** The last executed turn's response; the only turn for a single-turn invocation. */
    private TryItOutCoreResponseDto response;

    private Long durationMs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String traceId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Grafana Explore URL for this trace (present only when Grafana integration is configured)",
            example = "http://grafana:3000/explore?...")
    private String grafanaTraceUrl;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Every executed invocation of a multi-invocation sequence (multi-turn and/or "
                    + "multi-request), in execution order: request-major, turn-minor — all turns of request #0, "
                    + "then all turns of request #1, and so on. Includes the last invocation, which duplicates "
                    + "the top-level resolvedRequest/response/durationMs/traceId/grafanaTraceUrl and stamps. "
                    + "On fail-fast the list contains only the invocations that actually ran, the failing one "
                    + "last. Omitted when exactly one invocation was planned (single-request suite with a "
                    + "single-turn test case, including a multi-turn test case that collapses to a single turn).")
    private List<TryItOutResponseDto> history;

    // Identity stamps + extraction results below are additive and NON_NULL-omitted: absent = not stamped /
    // no extraction performed.

    /** 0-based request position within the chain; stamped only when the chain has more than one request. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "0-based position of this invocation's request within the suite's request chain. "
                    + "Present only when the chain has more than one request; a single-request suite never "
                    + "serializes it.",
            example = "1")
    private Integer requestIndex;

    /** Chain length; stamped only when the chain has more than one request. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Total number of requests in the suite's chain (the suite's own request plus its "
                    + "additionalRequests). Present only when the chain has more than one request.",
            example = "2")
    private Integer totalRequests;

    /** Request label; stamped only when the chain has more than one request AND the request is labelled. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Label of this invocation's request: the suite-level requestName for request #0, the "
                    + "additionalRequests entry's name otherwise. Present only when the chain has more than one "
                    + "request AND the request is labelled. Try-out-only convenience — persisted run rows carry "
                    + "no request name.",
            example = "followup")
    private String requestName;

    /** 0-based turn position; stamped only when this invocation's request planned more than one turn. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "0-based turn position of this invocation within its request's turn sequence. "
                    + "Present only when that request planned more than one turn; single-turn invocations "
                    + "(including the no-per-turn-binding collapse) never serialize it.",
            example = "0")
    private Integer turnIndex;

    /** Planned turn count; stamped only when this invocation's request planned more than one turn. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Number of turns this invocation's request planned. Present only when that request "
                    + "planned more than one turn.",
            example = "2")
    private Integer totalTurns;

    /**
     * This invocation's own reconciled extraction (not the accumulated frame), parsed verbatim from the
     * extractor's null-preserving JSON output — typed {@link JsonNode} so explicit JSON nulls survive the
     * shared mapper's NON_NULL content inclusion. Absent when no extraction was performed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "This invocation's own reconciled per-column extraction over its request's "
                    + "response-column definitions — this invocation's own extraction, not the accumulated "
                    + "frame carried between requests. A column whose extraction failed appears with an "
                    + "explicit JSON null value. Omitted when no extraction was performed: the suite defines "
                    + "no response columns, the invocation failed, or the try-out is MCP.",
            example = "{\"configId\": \"cfg-42\", \"summary\": null}")
    private JsonNode extractedColumns;

    /** This invocation's extraction warnings, parsed verbatim. Absent when no extraction was performed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "This invocation's extraction warnings (one per column whose extraction failed, "
                    + "with the column name, its JSONata expression, and the error). Empty list when extraction "
                    + "ran without warnings. Omitted when no extraction was performed.",
            example = "[{\"column\": \"summary\", \"expression\": \"$.missing.path\", "
                    + "\"error\": \"Expression matched nothing\"}]")
    private JsonNode extractionWarnings;
}
