package com.epam.aidial.evaluation.experimental.query.service.repository;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Registry of {@link StructuredQueryEntityResolver}s, built once at startup from every resolver bean
 * present (which depends on {@code @ConditionalOnProperty} datasource gating). The lookup root for
 * {@code StructuredQueryBuilder} and {@code StructuredQueryExecutor} — both resolve a
 * {@code StructuredQuery}'s {@code dsl}/{@code table}/{@code bindings} purely from {@code
 * query.entity()} via this registry, recursively for nested subqueries.
 */
@Component
@LogExecution
public class StructuredQueryEntityRegistry {

    private final Map<String, StructuredQueryEntityResolver> resolversByEntity;

    public StructuredQueryEntityRegistry(List<StructuredQueryEntityResolver> resolvers) {
        final Map<String, StructuredQueryEntityResolver> byEntity = new TreeMap<>();
        for (final StructuredQueryEntityResolver resolver : resolvers) {
            final StructuredQueryEntityResolver duplicate = byEntity.put(resolver.entity(), resolver);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate structured query entity resolver for entity: " + resolver.entity());
            }
        }
        this.resolversByEntity = byEntity;
    }

    /**
     * @throws ValidationException if {@code entity} has no registered resolver, listing the supported
     *     entities
     */
    public StructuredQueryEntityResolver require(String entity) {
        final StructuredQueryEntityResolver resolver = resolversByEntity.get(entity);
        if (resolver == null) {
            throw new ValidationException(
                    "entity '" + entity + "' is not queryable; supported entities: " + resolversByEntity.keySet());
        }
        return resolver;
    }

    /** The entities this registry can currently resolve, in stable order. */
    public Set<String> supportedEntities() {
        return Set.copyOf(resolversByEntity.keySet());
    }
}
