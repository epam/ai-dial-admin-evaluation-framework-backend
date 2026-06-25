package com.epam.aidial.evaluation.experimental.query.service;

import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import java.util.List;
import java.util.Map;

/**
 * Publishes the flat, queryable schema of one entity for the structured query DSL. Implementations
 * are Spring {@code @Component}s collected by {@link QueryEntityRegistry}; registering a new entity
 * means adding a provider, not touching the web layer.
 */
public interface QueryableEntitySchemaProvider {

    /** Entity identity: wire name, complexity flag, and the detailed-schema id field (if complex). */
    QueryEntityDto descriptor();

    /** Instance-independent flat schema; JSONB fields are listed as-is ({@code object}/{@code array}). */
    List<QuerySchemaFieldDto> baseSchema();

    /**
     * Instance-specific flat schema with JSONB fields flattened (complex entities only). Called by
     * the registry only when {@link #descriptor()} declares the entity complex.
     */
    default List<QuerySchemaFieldDto> detailedSchema(Map<String, String> params) {
        throw new UnsupportedOperationException(
                "Entity '" + descriptor().name() + "' does not provide a detailed schema");
    }
}
