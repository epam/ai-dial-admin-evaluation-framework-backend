package com.epam.aidial.evaluation.query.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A queryable entity as listed by {@code GET /api/v1/queries/entities}. {@code name} is the wire
 * identifier used as the {@code entity} value of a structured query. Complex entities carry JSONB
 * fields whose flattened schema depends on a concrete instance; {@code schemaIdField} names the
 * field whose value selects that instance for {@code GET .../schema/{name}/detailed} (e.g.
 * {@code test_suite_run_id} for {@code eval_summaries}); it is {@code null} for simple entities.
 */
public record QueryEntityDto(
        @Schema(description = "Entity wire name", example = "eval_summaries")
        String name,

        @Schema(description = "Whether the entity has an instance-specific detailed schema")
        boolean complex,

        @Schema(
                description = "Field whose value identifies the instance for the detailed schema endpoint",
                example = "test_suite_run_id")
        String schemaIdField) {}
