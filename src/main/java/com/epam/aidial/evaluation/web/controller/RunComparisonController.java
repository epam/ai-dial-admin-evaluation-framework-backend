package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.RunComparisonProvider;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunComparisonResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compares two runs of one suite over the eval-summary rows they have in common.
 *
 * <p>Depends on {@link RunComparisonProvider}, never on the implementation: the implementation lives in the
 * experimental query package, and this controller must stay in the stable web layer so it can raise the shared
 * exception types. See {@code LayeredArchitectureTest}.
 */
@Slf4j
@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/analytics/metric-scores")
@RequiredArgsConstructor
@Tag(name = "Run Comparison", description = "Metric scores recomputed over two runs' shared test cases")
public class RunComparisonController {

    private final RunComparisonProvider runComparisonProvider;

    @GetMapping(value = "/comparison", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Compare two runs over their shared test cases",
            description = "Recomputes each run's metric-score statistics, `overall` and average execution "
                    + "duration over only the eval-summary rows whose match key "
                    + "(`lower(test_case_name)` + `run_index` + `turn_index`) also occurs in the other run, so "
                    + "the two runs' numbers describe the same population and are comparable. Nothing is "
                    + "persisted — the run's own full-population `metric_score_results` are untouched.\n\n"
                    + "Both runs must belong to the same suite. Each run's **latest** computation is used; "
                    + "there is no override. Run status is not gated: a CANCELLED or partially-completed run "
                    + "still has rows worth comparing.\n\n"
                    + "`unmatchedEvalSummaryIds` names the rows that did **not** match, so a follow-up query "
                    + "over `POST /api/v1/queries/execute` reproduces the compared population by *excluding* "
                    + "them. There is no `not_in` operator — wrap `in` in a `not` node:\n\n"
                    + "```json\n"
                    + "{\n"
                    + "  \"op\": \"not\",\n"
                    + "  \"nodes\": [\n"
                    + "    {\n"
                    + "      \"op\": \"in\",\n"
                    + "      \"args\": [\n"
                    + "        {\"type\": \"field\", \"name\": \"id\"},\n"
                    + "        {\"type\": \"array\", \"items\": ["
                    + "{\"type\": \"value\", \"value_type\": \"uuid\", \"value\": \"…\"}]}\n"
                    + "      ]\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n"
                    + "```\n\n"
                    + "An empty `unmatchedEvalSummaryIds` means every row matched, so no filter is needed.")
    @ApiResponse(
            responseCode = "200",
            description = "Both runs' matched-row counts and recomputed scores, in the requested order",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RunComparisonResponseDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "`runIds` is absent, does not hold exactly two ids, is not made of UUIDs, "
                    + "or names the same run twice")
    @ApiResponse(responseCode = "404", description = "Either run id is unknown")
    @ApiResponse(
            responseCode = "409",
            description = "The runs belong to different suites, a run has no metric computation, or a run's "
                    + "non-matching row count exceeds `analytics.comparison.max-unmatched-rows`")
    @ApiResponse(
            responseCode = "422",
            description = "Either run has no `suite_snapshot` (legacy runs cannot be compared)")
    public RunComparisonResponseDto compare(
            @Parameter(
                            description = "Exactly two distinct run ids of the same suite, in the order they "
                                    + "should be reported",
                            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6,9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
                    @RequestParam
                    @Size(min = 2, max = 2)
                    List<UUID> runIds) {
        return runComparisonProvider.compare(runIds);
    }
}
