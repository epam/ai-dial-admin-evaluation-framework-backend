package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.translate.StructuredQueryBuilder;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;

/**
 * Entity-agnostic execution engine. Resolves the query's entity via
 * {@link StructuredQueryEntityRegistry}, applies that entity's pre-translation rewrite (if any),
 * builds the SQL via {@link StructuredQueryBuilder}, runs it, and surfaces the projected rows plus an
 * optional total count.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class StructuredQueryExecutor {

    private final StructuredQueryBuilder queryBuilder;
    private final StructuredQueryEntityRegistry entityRegistry;

    /**
     * @throws ValidationException if the query is null, names an unregistered entity, uses an
     *     unsupported field/function/feature, or is rejected by the database as not type-checking
     *     against the data (e.g. aggregating a non-numeric JSONB field) — all client errors (HTTP 400)
     */
    public QueryResultPage execute(StructuredQuery rawQuery) {
        if (rawQuery == null) {
            throw new ValidationException("query must not be null");
        }
        final StructuredQueryEntityResolver resolver = entityRegistry.require(rawQuery.entity());
        final StructuredQuery query = resolver.rewrite(rawQuery);

        // build() validates fields/functions/features and may throw ValidationException — let it propagate.
        final SelectQuery<Record> select = queryBuilder.build(query);
        try {
            final List<Map<String, Object>> rows = select.fetch().intoMaps();
            final Long totalCount = totalCount(query);
            log.debug(
                    "Executed structured {} query: {} row(s), totalCount={}", query.entity(), rows.size(), totalCount);
            return new QueryResultPage(rows, totalCount);
        } catch (BadSqlGrammarException | DataIntegrityViolationException e) {
            // The SQL is well-formed but does not type-check against the data (e.g. avg() of a JSONB
            // object, or casting a non-numeric value to numeric). That is a client query error, not a
            // server fault, so surface it as HTTP 400 rather than a 500.
            log.warn("Structured {} query rejected by the database: {}", query.entity(), e.getMessage(), e);
            throw new ValidationException(
                    "the structured query could not be executed; check field/function/type compatibility: "
                            + mostSpecificMessage(e));
        }
    }

    private static String mostSpecificMessage(NestedRuntimeException e) {
        final Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null ? cause.getMessage() : e.getMessage();
    }

    private Long totalCount(StructuredQuery query) {
        // Total count is meaningful for offset row paging; group counts in aggregate mode are out of scope.
        if (query.mode() == QueryMode.AGGREGATE) {
            return null;
        }
        if (query.page() instanceof OffsetPage offset && offset.includeTotal()) {
            return (long) queryBuilder.countRows(query);
        }
        return null;
    }
}
