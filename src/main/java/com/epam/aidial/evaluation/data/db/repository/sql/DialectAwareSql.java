package com.epam.aidial.evaluation.data.db.repository.sql;

import java.util.function.Function;
import org.jooq.Condition;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.CustomCondition;
import org.jooq.impl.CustomField;

/**
 * Builds jOOQ {@link Condition}/{@link Field} objects that pick their rendered SQL at
 * <b>render time</b> from the current {@link org.jooq.Context}'s {@link SQLDialect#family()}, rather
 * than at bean-wiring time.
 *
 * <p>Some SQL-building code (e.g. {@code JsonPathAccessor}, {@code FilterTranslator}) is shared
 * between the meta datasource (always Postgres) and the analytics datasource (Postgres or
 * ClickHouse, selected by {@code datasource.analytics.vendor}). A vendor-gated Spring bean cannot
 * express this: the same {@code FilterTranslator} instance renders both meta and analytics queries
 * within a single request, so the SQL dialect is a property of the query being rendered, not of the
 * component. jOOQ's {@link CustomCondition}/{@link CustomField} extension points solve this: they
 * defer to a callback that runs when the surrounding {@link org.jooq.Context} actually renders the
 * SQL string, at which point {@code ctx.family()} reports the dialect of *that* render call.
 *
 * <p>Every {@code byFamily} function passed here MUST treat {@link SQLDialect#CLICKHOUSE} as the only
 * special case and render today's Postgres SQL, byte-identical, for every other family (including
 * {@link SQLDialect#DEFAULT}) — this is what keeps the existing Postgres render-pinning unit tests
 * passing unmodified.
 */
public final class DialectAwareSql {

    private DialectAwareSql() {}

    /** A {@link Condition} that renders {@code byFamily.apply(ctx.family())} at render time. */
    public static Condition condition(Function<SQLDialect, Condition> byFamily) {
        return CustomCondition.of(ctx -> ctx.visit(byFamily.apply(ctx.family())));
    }

    /** A {@link Field} that renders {@code byFamily.apply(ctx.family())} at render time. */
    public static <T> Field<T> field(String name, DataType<T> type, Function<SQLDialect, Field<T>> byFamily) {
        return CustomField.of(name, type, ctx -> ctx.visit(byFamily.apply(ctx.family())));
    }
}
