package com.epam.aidial.evaluation.query.service;

import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import org.springframework.stereotype.Component;

/**
 * Maps a dataset / response-column declared {@link SchemaFieldType} onto the DSL-aligned
 * {@link QueryFieldType} vocabulary. Shared by every schema provider / bindings builder that
 * flattens JSONB families, so the schema advertised by discovery and the types used at translation
 * stay single-sourced. A {@code null} type defaults to {@link QueryFieldType#STRING}.
 */
@Component
@LogExecution
public class SchemaFieldTypeMapper {

    public QueryFieldType map(SchemaFieldType type) {
        if (type == null) {
            return QueryFieldType.STRING;
        }
        return switch (type) {
            case STRING, FILE -> QueryFieldType.STRING;
            case INTEGER -> QueryFieldType.LONG;
            case NUMBER -> QueryFieldType.DECIMAL;
            case BOOLEAN -> QueryFieldType.BOOLEAN;
            case OBJECT -> QueryFieldType.OBJECT;
            case ARRAY -> QueryFieldType.ARRAY;
        };
    }
}
