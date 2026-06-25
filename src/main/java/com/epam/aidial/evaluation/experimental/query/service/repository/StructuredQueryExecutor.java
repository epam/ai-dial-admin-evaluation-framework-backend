package com.epam.aidial.evaluation.experimental.query.service.repository;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.OffsetPage;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.experimental.query.service.translate.StructuredQueryBuilder;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.Table;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;

/**
 * Datasource- and entity-agnostic execution engine shared by every {@link StructuredQueryRepository}.
 * It validates the request, resolves the table's field bindings (cached per generated table), builds
 * the SQL via {@link StructuredQueryBuilder}, runs it on the caller-supplied {@link DSLContext}, and
 * surfaces the projected rows plus an optional total count. Keeping this logic here means a new
 * queryable entity is a few lines binding its name to a table and {@code DSLContext} — no duplicated
 * fetch/count/binding plumbing.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class StructuredQueryExecutor {

    private final StructuredQueryBuilder queryBuilder;
    private final JooqTableSchemaResolver schemaResolver;

    /** Field bindings are derived once per generated table and reused across requests. */
    private final Map<Table<?>, Map<String, QueryFieldBinding>> bindingsCache = new ConcurrentHashMap<>();

    /**
     * Translates and runs {@code query} against {@code table} on {@code dsl}.
     *
     * @param entity the entity this engine call serves; {@code query.entity()} must equal it
     * @throws ValidationException if the query is null, targets a different entity, uses an
     *     unsupported field/function/feature, or is rejected by the database as not type-checking
     *     against the data (e.g. aggregating a non-numeric JSONB field) — all client errors (HTTP 400)
     */
    public QueryResultPage execute(String entity, DSLContext dsl, Table<?> table, StructuredQuery query) {
        return execute(entity, dsl, table, query, Map.of());
    }

    /**
     * Translates and runs {@code query} against {@code table} on {@code dsl}, resolving {@code param}
     * expressions against {@code params} (parameter = expression substitution). Used by internal
     * callers; the public execute path supplies an empty map.
     */
    public QueryResultPage execute(
            String entity, DSLContext dsl, Table<?> table, StructuredQuery query, Map<String, Expr> params) {
        if (query == null) {
            throw new ValidationException("query must not be null");
        }
        if (!entity.equals(query.entity())) {
            throw new ValidationException("the experimental " + entity + " query repository only supports entity '"
                    + entity + "', got '" + query.entity() + "'");
        }

        final Map<String, QueryFieldBinding> bindings = bindings(table);
        // build() validates fields/functions/features and may throw ValidationException — let it propagate.
        final SelectQuery<Record> select = queryBuilder.build(dsl, table, bindings, query, params);
        try {
            final List<Map<String, Object>> rows = select.fetch().intoMaps();
            final Long totalCount = totalCount(dsl, table, bindings, query, params);
            log.debug("Executed structured {} query: {} row(s), totalCount={}", entity, rows.size(), totalCount);
            return new QueryResultPage(rows, totalCount);
        } catch (BadSqlGrammarException | DataIntegrityViolationException e) {
            // The SQL is well-formed but does not type-check against the data (e.g. avg() of a JSONB
            // object, or casting a non-numeric value to numeric). That is a client query error, not a
            // server fault, so surface it as HTTP 400 rather than a 500.
            log.warn("Structured {} query rejected by the database: {}", entity, e.getMessage(), e);
            throw new ValidationException(
                    "the structured query could not be executed; check field/function/type compatibility: "
                            + mostSpecificMessage(e));
        }
    }

    private static String mostSpecificMessage(NestedRuntimeException e) {
        final Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null ? cause.getMessage() : e.getMessage();
    }

    private Long totalCount(
            DSLContext dsl,
            Table<?> table,
            Map<String, QueryFieldBinding> bindings,
            StructuredQuery query,
            Map<String, Expr> params) {
        // Total count is meaningful for offset row paging; group counts in aggregate mode are out of scope.
        if (query.mode() == QueryMode.AGGREGATE) {
            return null;
        }
        if (query.page() instanceof OffsetPage offset && offset.includeTotal()) {
            return (long) queryBuilder.countRows(dsl, table, bindings, query, params);
        }
        return null;
    }

    private Map<String, QueryFieldBinding> bindings(Table<?> table) {
        return bindingsCache.computeIfAbsent(table, schemaResolver::bindings);
    }
}
