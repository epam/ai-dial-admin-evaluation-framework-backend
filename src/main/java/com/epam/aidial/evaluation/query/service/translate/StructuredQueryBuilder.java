package com.epam.aidial.evaluation.query.service.translate;

import com.epam.aidial.evaluation.query.model.CursorPage;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.NullsOrder;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.PageSpec;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.SortDir;
import com.epam.aidial.evaluation.query.model.SortItem;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.SubqueryExpr;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryEntityRegistry;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryEntityResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.SelectQuery;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Assembles a complete jOOQ {@link SelectQuery} from a {@link StructuredQuery} for a single table.
 * Built on jOOQ's mutable model API ({@link DSLContext#selectQuery()}), which suits dynamic
 * construction far better than the fluent step API. Supports both query modes (§2):
 *
 * <ul>
 *   <li><strong>row</strong> — projection from {@code select} (or all columns when empty), {@code
 *       filter}, {@code sort}, {@code page};
 *   <li><strong>aggregate</strong> — {@code group_by} keys plus aggregate-function {@code select}
 *       entries, with {@code having} and {@code sort} resolved against group keys and aggregate
 *       aliases.
 * </ul>
 *
 * <p>Generic over the table/bindings so it is reusable across entities (wired for {@code
 * test_suites} on the meta datasource and {@code eval_summaries} on analytics). Pagination is
 * offset-only; cursor paging is rejected with a clear message.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class StructuredQueryBuilder {

    private static final int DEFAULT_LIMIT = 100;

    /** The hard cap {@link #applyPage} clamps any {@code OffsetPage.limit()} to, default included. */
    public static final int MAX_LIMIT = 1000;

    private final ExprTranslator exprTranslator;
    private final FilterTranslator filterTranslator;
    private final StructuredQueryEntityRegistry entityRegistry;

    /**
     * Builds the executable select for {@code query}, resolving its {@code dsl}/{@code table}/
     * {@code bindings} from {@code query.entity()} via {@link StructuredQueryEntityRegistry}. The
     * query must already be parameter-free ({@code QueryParameterResolver} runs before this).
     */
    public SelectQuery<Record> build(StructuredQuery query) {
        final StructuredQueryEntityResolver resolver = entityRegistry.require(query.entity());
        final DSLContext dsl = resolver.dsl();
        final Table<?> table = resolver.table();
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(query);

        final SelectQuery<Record> select = dsl.selectQuery();
        select.addFrom(table);
        select.addConditions(filterTranslator.toCondition(query.filter(), bindings));

        final QueryMode mode = query.mode();
        // Names that are select outputs (aliases / projected fields), so sort can reference the output
        // column instead of re-translating its expression.
        final Set<String> selectAliases = new HashSet<>();
        final Map<String, QueryFieldBinding> sortBindings = mode == QueryMode.AGGREGATE
                ? buildAggregate(select, bindings, query, selectAliases)
                : buildRow(select, table, bindings, query, selectAliases);

        if (query.distinct()) {
            select.setDistinct(true);
        }
        select.addOrderBy(sortFields(query.sort(), sortBindings, selectAliases));
        applyPage(select, query.page());
        return select;
    }

    /** Counts matching rows for offset {@code include_total} (row mode only; ignores paging). */
    public int countRows(StructuredQuery query) {
        final StructuredQueryEntityResolver resolver = entityRegistry.require(query.entity());
        final DSLContext dsl = resolver.dsl();
        final Table<?> table = resolver.table();
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(query);
        final Condition where = filterTranslator.toCondition(query.filter(), bindings);
        final Integer count = dsl.selectCount().from(table).where(where).fetchOne(0, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Compiles {@code subquery} into a nested single-column select — its first select column is the
     * value/membership key; any additional columns exist only to drive the inner query's own
     * {@code ORDER BY}/{@code LIMIT}. Called (via {@link ExprTranslator}'s lazy reference to this
     * bean) from anywhere a {@code subquery} expression appears. No same-entity check: a
     * cross-datasource subquery fails naturally at the database with a normal SQL error, already
     * mapped to HTTP 400 by {@code StructuredQueryExecutor}.
     */
    Select<? extends Record1<?>> compileSubqueryMembership(SubqueryExpr subquery) {
        final StructuredQuery inner = subquery.query();
        if (inner == null) {
            throw new ValidationException("'subquery' requires a 'query'");
        }
        final SelectQuery<Record> subselect = build(inner);
        final Table<?> derived = subselect.asTable(DSL.name("in_subquery"));
        final Field<?> key = derived.field(0);
        if (key == null) {
            throw new ValidationException("'subquery' must select at least one column");
        }
        return subselect.configuration().dsl().select(key).from(derived);
    }

    private Map<String, QueryFieldBinding> buildRow(
            SelectQuery<Record> select,
            Table<?> table,
            Map<String, QueryFieldBinding> bindings,
            StructuredQuery query,
            Set<String> selectAliases) {
        final List<OutputColumn> projection = query.select();
        if (projection == null || projection.isEmpty()) {
            select.addSelect(List.of(table.fields()));
        } else {
            final List<Field<?>> fields = new ArrayList<>(projection.size());
            for (final OutputColumn col : projection) {
                final Field<?> field = exprTranslator.toField(col.expr(), bindings);
                final String alias = col.as() != null
                        ? col.as()
                        : col.expr() instanceof FieldExpr(String fieldName) ? fieldName : null;
                if (alias != null) {
                    fields.add(field.as(alias));
                    selectAliases.add(alias);
                } else {
                    fields.add(field);
                }
            }
            select.addSelect(fields);
        }
        return bindings;
    }

    private Map<String, QueryFieldBinding> buildAggregate(
            SelectQuery<Record> select,
            Map<String, QueryFieldBinding> bindings,
            StructuredQuery query,
            Set<String> selectAliases) {
        final List<String> groupBy = query.groupBy() == null ? List.of() : query.groupBy();
        final List<OutputColumn> selectEntries = query.select() == null ? List.of() : query.select();
        if (selectEntries.isEmpty()) {
            throw new ValidationException(
                    "aggregate mode requires at least one select entry (group-key projection or aggregate call)");
        }

        // having may reference aggregate aliases as well as group keys, so expose both via aliasBindings;
        // group_by and sort may also reference a select output (e.g. a width_bucket expression aliased
        // "bucket"), so track output alias names for group-key and sort resolution.
        final Map<String, QueryFieldBinding> aliasBindings = new HashMap<>(bindings);
        final List<Field<?>> selectFields = new ArrayList<>();
        for (final OutputColumn col : selectEntries) {
            if (col.expr() instanceof FieldExpr(String fieldName) && groupBy.contains(fieldName)) {
                final Field<?> field = exprTranslator.resolveField(fieldName, bindings);
                final String alias = col.as() != null ? col.as() : fieldName;
                selectFields.add(field.as(alias));
                selectAliases.add(alias);
            } else {
                final Field<?> expr = exprTranslator.toField(col.expr(), bindings);
                final String alias = requireAlias(col);
                selectFields.add(expr.as(alias));
                aliasBindings.put(alias, new QueryFieldBinding(alias, expr, QueryFieldType.DECIMAL));
                selectAliases.add(alias);
            }
        }

        final List<Field<?>> groupFields = new ArrayList<>();
        for (final String key : groupBy) {
            groupFields.add(resolveGroupKey(key, bindings, selectAliases));
        }

        select.addSelect(selectFields);
        select.addGroupBy(groupFields);
        if (query.having() != null) {
            select.addHaving(filterTranslator.toCondition(query.having(), aliasBindings));
        }
        return aliasBindings;
    }

    /**
     * Resolves a group-by key to the field to group by. A key that names a select output is grouped by
     * its output-column reference ({@code GROUP BY "bucket"}) rather than by re-translating the
     * expression: re-inlining would emit fresh bind parameters (e.g. for JSONB keys), and PostgreSQL
     * would not recognize the GROUP BY and SELECT occurrences as the same expression ("must appear in
     * the GROUP BY clause"). A key that is not a select output is a plain physical/JSONB column.
     */
    private Field<?> resolveGroupKey(String key, Map<String, QueryFieldBinding> bindings, Set<String> selectAliases) {
        if (selectAliases.contains(key)) {
            return DSL.field(DSL.name(key));
        }
        final Field<?> bound = exprTranslator.resolveFieldOrNull(key, bindings);
        if (bound != null) {
            return bound;
        }
        throw new ValidationException("unknown group-by field '" + key + "'");
    }

    private List<SortField<?>> sortFields(
            List<SortItem> sort, Map<String, QueryFieldBinding> bindings, Set<String> selectAliases) {
        if (sort == null || sort.isEmpty()) {
            return List.of();
        }
        final List<SortField<?>> fields = new ArrayList<>(sort.size());
        for (final SortItem item : sort) {
            // A sort key naming a select output is ordered by its output-column reference rather than
            // by re-translating the expression: re-inlining a computed/JSONB expression would emit fresh
            // bind parameters and, in aggregate mode, no longer match the GROUP BY expression.
            final Field<?> field = selectAliases.contains(item.field())
                    ? DSL.field(DSL.name(item.field()))
                    : exprTranslator.resolveField(item.field(), bindings);
            SortField<?> sortField = item.dir() == SortDir.DESC ? field.desc() : field.asc();
            // Null ordering is client-controlled (§6, D8); when unspecified we emit no NULLS clause
            // and let the database default apply (ASC → NULLS LAST, DESC → NULLS FIRST).
            if (item.nulls() == NullsOrder.FIRST) {
                sortField = sortField.nullsFirst();
            } else if (item.nulls() == NullsOrder.LAST) {
                sortField = sortField.nullsLast();
            }
            fields.add(sortField);
        }
        return fields;
    }

    private void applyPage(SelectQuery<Record> select, PageSpec page) {
        switch (page) {
            case null -> select.addLimit(DEFAULT_LIMIT);
            case OffsetPage offset -> {
                if (offset.offset() < 0) {
                    throw new ValidationException("page offset must not be negative");
                }
                select.addLimit(offset.offset(), clampLimit(offset.limit()));
            }
            case CursorPage ignored ->
                throw new ValidationException(
                        "cursor pagination is not supported by the query translator; use offset paging");
        }
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    private static String requireAlias(OutputColumn col) {
        if (col.as() == null || col.as().isBlank()) {
            throw new ValidationException("aggregate output column requires an 'as' alias");
        }
        return col.as();
    }
}
