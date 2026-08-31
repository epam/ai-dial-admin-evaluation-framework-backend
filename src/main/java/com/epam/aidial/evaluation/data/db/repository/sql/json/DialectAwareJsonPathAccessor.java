package com.epam.aidial.evaluation.data.db.repository.sql.json;

import com.epam.aidial.evaluation.data.db.repository.sql.ClickHouseTypeNames;
import com.epam.aidial.evaluation.data.db.repository.sql.DialectAwareSql;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.math.BigDecimal;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/**
 * Renders JSONB path access for both datasources this accessor's consumers serve: the meta
 * datasource (always Postgres) and the analytics datasource (Postgres or ClickHouse, selected by
 * {@code datasource.analytics.vendor}). Because the same {@code WhereBuilder}/{@code
 * JsonbFieldResolver} instances build both meta and analytics queries, the dialect can only be
 * decided at render time — see {@link DialectAwareSql}. Every method below therefore renders
 * today's Postgres expression, byte-identical, for every family except {@link
 * SQLDialect#CLICKHOUSE}.
 *
 * <p>ClickHouse stores the JSONB-typed columns as {@code String} (see the analytics vendor's V1.1
 * migration), so the CLICKHOUSE branches operate on a JSON-text argument via {@code JSONExtract*}
 * functions rather than a native JSON type.
 *
 * <p><b>{@link #jsonbAtAsText} caveat:</b> Postgres {@code ->>} returns the full JSON text
 * rendering of the value at the key, whether it is a scalar or a nested object/array. This
 * implementation's ClickHouse branch uses {@code JSONExtract(col, key, 'Nullable(String)')}, which
 * per ClickHouse's documented behaviour extracts scalar strings/numbers as text; it is not verified
 * here whether it renders a nested object/array the same way Postgres does (that would require
 * {@code JSONExtractRaw} instead). {@code JSON_VALUE(col, '$.<key>')} — which needs the key
 * INLINED because JSON_VALUE paths must be a compile-time constant — is the documented fallback if
 * a live ClickHouse instance shows {@code JSONExtract} returning {@code NULL} for non-string
 * scalars or diverging from Postgres on nested values. P4's functional test suite against a real
 * ClickHouse container adjudicates which form is actually correct.
 */
@Component
@LogExecution
public class DialectAwareJsonPathAccessor implements JsonPathAccessor {

    @Override
    public Field<JSONB> jsonbAt(Field<JSONB> column, Field<String> key) {
        return DialectAwareSql.field(
                "jsonb_at",
                SQLDataType.JSONB,
                family -> family == SQLDialect.CLICKHOUSE
                        ? DSL.function("JSONExtractRaw", SQLDataType.JSONB, column, key)
                        : DSL.jsonbGetAttribute(column, key));
    }

    @Override
    public Field<String> jsonbAtAsText(Field<JSONB> column, Field<String> key) {
        return DialectAwareSql.field(
                "jsonb_at_as_text",
                SQLDataType.VARCHAR,
                family -> family == SQLDialect.CLICKHOUSE
                        ? DSL.function(
                                "JSONExtract",
                                SQLDataType.VARCHAR,
                                column,
                                key,
                                DSL.inline(ClickHouseTypeNames.NULLABLE_STRING))
                        : DSL.jsonbGetAttributeAsText(column, key));
    }

    @Override
    public Field<BigDecimal> jsonbAtAsNumeric(Field<JSONB> column, Field<String> key1, Field<String> key2) {
        return DialectAwareSql.field(
                "jsonb_at_as_numeric",
                SQLDataType.NUMERIC,
                family -> family == SQLDialect.CLICKHOUSE
                        ? DSL.function(
                                "JSONExtract",
                                SQLDataType.NUMERIC,
                                column,
                                key1,
                                key2,
                                DSL.inline(ClickHouseTypeNames.NULLABLE_FLOAT64))
                        : DSL.jsonbGetAttributeAsText(DSL.jsonbGetAttribute(column, key1), key2)
                                .cast(SQLDataType.NUMERIC));
    }
}
