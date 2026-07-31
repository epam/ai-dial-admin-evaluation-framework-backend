package com.epam.aidial.evaluation.experimental.query.service;

import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntitySchemaDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Registry of {@link QueryableEntitySchemaProvider}s — the lookup root for the schema discovery
 * API. Entities are listed in stable (alphabetical) order; an unknown entity name is a 404 and a
 * detailed-schema request against a simple entity is a 400.
 */
@Component
@LogExecution
public class QueryEntityRegistry {

    private final Map<String, QueryableEntitySchemaProvider> providers = new TreeMap<>();

    public QueryEntityRegistry(List<QueryableEntitySchemaProvider> providerList) {
        for (QueryableEntitySchemaProvider provider : providerList) {
            final String name = provider.descriptor().name();
            final QueryableEntitySchemaProvider duplicate = providers.put(name, provider);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate queryable entity registration: " + name);
            }
        }
    }

    public List<QueryEntityDto> listEntities() {
        return providers.values().stream()
                .map(QueryableEntitySchemaProvider::descriptor)
                .toList();
    }

    public QueryEntitySchemaDto getBaseSchema(String entityName) {
        final QueryableEntitySchemaProvider provider = requireProvider(entityName);
        final QueryEntityDto descriptor = provider.descriptor();
        return new QueryEntitySchemaDto(
                descriptor.name(), descriptor.complex(), descriptor.schemaIdField(), provider.baseSchema());
    }

    public QueryEntitySchemaDto getDetailedSchema(String entityName, Map<String, String> params) {
        final QueryableEntitySchemaProvider provider = requireProvider(entityName);
        final QueryEntityDto descriptor = provider.descriptor();
        if (!descriptor.complex()) {
            throw new ValidationException("Entity '" + entityName
                    + "' is not complex and has no detailed schema; use GET /api/v1/queries/entities/schema/"
                    + entityName);
        }
        return new QueryEntitySchemaDto(
                descriptor.name(), descriptor.complex(), descriptor.schemaIdField(), provider.detailedSchema(params));
    }

    private QueryableEntitySchemaProvider requireProvider(String entityName) {
        final QueryableEntitySchemaProvider provider = providers.get(entityName);
        if (provider == null) {
            throw new EntityNotFoundException("Unknown queryable entity: " + entityName);
        }
        return provider;
    }
}
