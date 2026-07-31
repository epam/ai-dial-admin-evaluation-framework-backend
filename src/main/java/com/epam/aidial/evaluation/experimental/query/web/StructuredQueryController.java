package com.epam.aidial.evaluation.experimental.query.web;

import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.experimental.query.service.dto.StructuredQueryResultDto;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executes structured queries for the experimental DSL ({@code /api/v1} = experimental, subject to
 * change). A single entity-agnostic endpoint: the {@code entity} in the request body selects which
 * backing table/datasource runs the query, so the same contract serves {@code test_suites} (meta)
 * and {@code eval_summaries} (analytics). Discover valid entities and field names via the schema
 * endpoints on {@link QuerySchemaController}.
 */
@Slf4j
@RestController
@LogExecution
@RequestMapping("/api/v1/queries")
@RequiredArgsConstructor
@Tag(
        name = "Structured Queries (experimental)",
        description = "Entity and flat-schema discovery for the structured query DSL. Experimental — may change.")
public class StructuredQueryController {

    private final StructuredQueryService queryService;
    private final JsonbRowConverter jsonbRowConverter;

    @PostMapping("/execute")
    @Operation(
            summary = "Execute a structured query",
            description = "Translates the structured query to SQL and runs it against the entity named in `entity`."
                    + " Returns the projected rows (JSONB columns as nested JSON) and, for row-mode offset paging"
                    + " with `include_total`, the total match count. Unknown entities, fields, functions, or"
                    + " unsupported features are rejected with HTTP 400.")
    @ApiResponse(
            responseCode = "200",
            description = "Query result",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = StructuredQueryResultDto.class)))
    @ApiResponse(responseCode = "400", description = "Unknown entity/field/function or unsupported query feature")
    public StructuredQueryResultDto execute(@RequestBody StructuredQuery query) {
        final QueryResultPage page = queryService.execute(query);
        return new StructuredQueryResultDto(jsonbRowConverter.toJsonRows(page.rows()), page.totalCount());
    }
}
