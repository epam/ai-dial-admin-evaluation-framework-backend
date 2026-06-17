package com.epam.aidial.evaluation.experimental.query.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Flat schema of a queryable entity. The base schema ({@code GET .../schema/{name}}) lists JSONB
 * fields as-is ({@code object}/{@code array}); the detailed schema
 * ({@code GET .../schema/{name}/{id}}, complex entities only) replaces them with flattened
 * instance-specific fields.
 */
public record QueryEntitySchemaDto(
        @Schema(description = "Entity wire name", example = "eval_summaries")
        String entity,

        @Schema(description = "Whether the entity has an instance-specific detailed schema")
        boolean complex,

        @Schema(
                description = "Field whose value identifies the instance for the detailed schema endpoint",
                example = "testSuiteId")
        String schemaIdField,

        @Schema(description = "Flat, queryable fields") List<QuerySchemaFieldDto> fields) {}
