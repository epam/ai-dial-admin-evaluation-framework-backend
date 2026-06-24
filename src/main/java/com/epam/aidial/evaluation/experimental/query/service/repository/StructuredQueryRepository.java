package com.epam.aidial.evaluation.experimental.query.service.repository;

import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;

/**
 * Executes a {@link StructuredQuery} against a single backing entity by translating it to jOOQ SQL.
 * There is one implementation per queryable entity; the shared translation/execution plumbing lives
 * in {@link StructuredQueryExecutor}, so an implementation only binds an entity name to its table and
 * {@code DSLContext}. {@code StructuredQueryService} dispatches across all implementations by
 * {@link #supportedEntity()}.
 */
public interface StructuredQueryRepository {

    /** The {@code entity} value this repository serves (e.g. {@code test_suites}). */
    String supportedEntity();

    /**
     * Translates and runs {@code query} (whose {@code entity} must equal {@link #supportedEntity()}).
     *
     * @throws com.epam.aidial.evaluation.service.domain.exception.ValidationException if the entity
     *     does not match or the query uses an unsupported field, function, or feature
     */
    QueryResultPage execute(StructuredQuery query);
}
