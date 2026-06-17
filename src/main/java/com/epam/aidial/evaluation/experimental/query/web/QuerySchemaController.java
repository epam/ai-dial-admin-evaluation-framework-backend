package com.epam.aidial.evaluation.experimental.query.web;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.service.QueryEntityRegistry;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntitySchemaDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Schema discovery for the experimental structured query DSL ({@code /api/v1} = experimental,
 * subject to change). Publishes which entities can be queried and which flat field names are valid
 * in a structured query's {@code filter}/{@code select}/{@code sort} sections.
 */
@Slf4j
@RestController
@LogExecution
@RequestMapping("/api/v1/queries")
@RequiredArgsConstructor
@Tag(
        name = "Structured Queries (experimental)",
        description = "Entity and flat-schema discovery for the structured query DSL. Experimental — may change.")
public class QuerySchemaController {

    private final QueryEntityRegistry registry;

    @GetMapping("/entities")
    @Operation(
            summary = "List queryable entities",
            description = "Lists the entities available as the `entity` value of a structured query. Complex"
                    + " entities carry a `schemaIdField` whose value selects the instance for the detailed"
                    + " schema endpoint.")
    @ApiResponse(
            responseCode = "200",
            description = "Queryable entities",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = QueryEntityDto.class)))
    public List<QueryEntityDto> listEntities() {
        return registry.listEntities();
    }

    @GetMapping("/entities/schema/{name}")
    @Operation(
            summary = "Get an entity's flat base schema",
            description = "Returns the instance-independent flat schema. JSONB-backed fields are listed as-is"
                    + " (`object`/`array`); for complex entities, fetch the instance-specific flattening from"
                    + " the detailed schema endpoint using the `schemaIdField` value.")
    @ApiResponse(
            responseCode = "200",
            description = "Entity schema",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = QueryEntitySchemaDto.class)))
    @ApiResponse(responseCode = "404", description = "Unknown entity")
    public QueryEntitySchemaDto getBaseSchema(
            @Parameter(description = "Entity wire name", example = "eval_summaries") @PathVariable String name) {
        return registry.getBaseSchema(name);
    }

    @GetMapping("/entities/schema/{name}/detailed")
    @Operation(
            summary = "Get a complex entity's detailed flat schema",
            description = "Returns the instance-specific flat schema with JSONB fields flattened from the"
                    + " identified instance. The instance is selected by query parameters whose names are"
                    + " entity-specific: send the parameter named by the entity's `schemaIdField`"
                    + " (discoverable via the entities listing). For `eval_summaries` that is"
                    + " `test_suite_run_id`, and the schema is derived from that run's snapshot (the dataset"
                    + " schema, response columns and metric definitions frozen at run time). Alternatively send"
                    + " `test_suite_id` to derive the schema from that suite's latest run.")
    @ApiResponse(
            responseCode = "200",
            description = "Detailed entity schema",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = QueryEntitySchemaDto.class)))
    @ApiResponse(responseCode = "400", description = "Entity is not complex or an instance parameter is malformed")
    @ApiResponse(responseCode = "404", description = "Unknown entity or unknown instance id")
    public QueryEntitySchemaDto getDetailedSchema(
            @Parameter(description = "Entity wire name", example = "eval_summaries") @PathVariable String name,
            @Parameter(
                            description = "Instance-selecting query parameters. The parameter to send is named"
                                    + " by the entity's `schemaIdField` (e.g. `test_suite_run_id` for"
                                    + " `eval_summaries`, which also accepts `test_suite_id` to target the"
                                    + " suite's latest run); different entities may accept different parameters.",
                            example = "test_suite_run_id=3fa85f64-5717-4562-b3fc-2c963f66afa6")
                    @RequestParam
                    Map<String, String> params) {
        return registry.getDetailedSchema(name, params);
    }
}
