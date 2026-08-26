package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Table;

/**
 * Resolves everything {@code StructuredQueryBuilder} needs to compile and run a {@link StructuredQuery}
 * for one entity: its datasource, its backing table, and its field bindings. There is one
 * implementation per queryable entity; {@link StructuredQueryEntityRegistry} dispatches by
 * {@link #entity()}. Which implementations exist depends on the configured datasources
 * ({@code @ConditionalOnProperty}), so the set of queryable entities is the set of present resolvers.
 */
public interface StructuredQueryEntityResolver {

    /** The {@code entity} value this resolver serves (e.g. {@code test_suites}). */
    String entity();

    /** The datasource this entity's table lives on. */
    DSLContext dsl();

    /** The generated jOOQ table this entity is backed by. */
    Table<?> table();

    /**
     * Field bindings for {@code query}. Most entities ignore {@code query} and return a bindings map
     * computed once at construction time (purely static per-table metadata); an instance-aware entity
     * (e.g. {@code test_cases}) derives its bindings from the query's own request-scoped content (its
     * {@code dataset_id} filter).
     *
     * @throws com.epam.aidial.evaluation.service.domain.exception.ValidationException if the query
     *     does not carry whatever this entity requires to resolve its bindings
     */
    Map<String, QueryFieldBinding> bindings(StructuredQuery query);

    /**
     * Pre-translation rewrite hook, run once against the top-level query before it reaches the
     * builder. Default is identity; {@code metric_score_results} overrides it to resolve the
     * {@code computation_id eq "latest"} sentinel.
     */
    default StructuredQuery rewrite(StructuredQuery query) {
        return query;
    }
}
