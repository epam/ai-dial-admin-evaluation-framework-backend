package com.epam.aidial.evaluation.query.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One flat, queryable field of an entity schema. {@code source} names the entity field the flat
 * field is backed by: a plain column's source is the field itself, while a flattened JSONB-derived
 * field points at the JSONB field it lives in (e.g. {@code metric::Accuracy::score} →
 * {@code metricValues}).
 */
public record QuerySchemaFieldDto(
        @Schema(description = "Flat field name usable in structured queries", example = "metric::Accuracy::score")
        String name,

        @Schema(description = "Declared field type", example = "decimal")
        QueryFieldType type,

        @Schema(description = "Entity field backing this flat field", example = "metricValues")
        String source) {}
