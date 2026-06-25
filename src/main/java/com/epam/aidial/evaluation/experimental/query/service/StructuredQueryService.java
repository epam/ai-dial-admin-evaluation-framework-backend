package com.epam.aidial.evaluation.experimental.query.service;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.experimental.query.service.repository.StructuredQueryRepository;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Entry point for executing a {@link StructuredQuery} against any queryable entity. Collects every
 * {@link StructuredQueryRepository} bean and dispatches by {@code entity} to the one that serves it,
 * giving the API a single, entity-agnostic execute surface. Which repositories exist depends on the
 * configured datasources ({@code @ConditionalOnProperty}), so the set of queryable entities is the
 * set of present repositories.
 */
@Component
@LogExecution
public class StructuredQueryService {

    private final Map<String, StructuredQueryRepository> repositoriesByEntity;

    public StructuredQueryService(List<StructuredQueryRepository> repositories) {
        final Map<String, StructuredQueryRepository> byEntity = new TreeMap<>();
        for (final StructuredQueryRepository repository : repositories) {
            final StructuredQueryRepository duplicate = byEntity.put(repository.supportedEntity(), repository);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate structured query repository for entity: " + repository.supportedEntity());
            }
        }
        this.repositoriesByEntity = byEntity;
    }

    /** Routes {@code query} to the repository for {@code query.entity()} and executes it. */
    public QueryResultPage execute(StructuredQuery query) {
        if (query == null) {
            throw new ValidationException("query must not be null");
        }
        final StructuredQueryRepository repository = repositoriesByEntity.get(query.entity());
        if (repository == null) {
            throw new ValidationException("entity '" + query.entity() + "' is not queryable; supported entities: "
                    + repositoriesByEntity.keySet());
        }
        return repository.execute(query);
    }

    /** The entities this service can currently query, in stable order. */
    public Set<String> supportedEntities() {
        return Set.copyOf(repositoriesByEntity.keySet());
    }
}
