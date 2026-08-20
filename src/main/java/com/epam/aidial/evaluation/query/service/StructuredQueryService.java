package com.epam.aidial.evaluation.query.service;

import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryEntityRegistry;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryExecutor;
import com.epam.aidial.evaluation.query.service.translate.QueryParameterResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Entry point for executing a {@link StructuredQuery} against any queryable entity. Delegates entity
 * resolution to {@link StructuredQueryEntityRegistry} and translation/execution to
 * {@link StructuredQueryExecutor}, giving the API a single, entity-agnostic execute surface. Which
 * entities are queryable depends on which {@code StructuredQueryEntityResolver} beans are present
 * (gated by {@code @ConditionalOnProperty} per datasource).
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class StructuredQueryService {

    private final StructuredQueryExecutor executor;
    private final StructuredQueryEntityRegistry entityRegistry;
    private final QueryParameterResolver parameterResolver;

    /** Executes {@code query} against its entity's resolver (no params). */
    public QueryResultPage execute(StructuredQuery query) {
        return execute(query, Map.of());
    }

    /**
     * Executes {@code query} against its entity's resolver. Any {@code param} expressions are
     * resolved against {@code params} in a single pre-pass ({@link QueryParameterResolver}) before
     * dispatch, so the resolver/translator never see a {@code param}.
     */
    public QueryResultPage execute(StructuredQuery query, Map<String, Expr> params) {
        if (query == null) {
            throw new ValidationException("query must not be null");
        }
        entityRegistry.require(query.entity());
        return executor.execute(parameterResolver.resolve(query, params));
    }

    /** The entities this service can currently query, in stable order. */
    public Set<String> supportedEntities() {
        return entityRegistry.supportedEntities();
    }
}
