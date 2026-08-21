package com.epam.aidial.evaluation.query.service;

import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Component;

/**
 * Derives an entity's flat base schema from its generated jOOQ {@link Table} so providers do not
 * hand-roll column lists that silently drift from migrations. Columns are emitted in DDL order;
 * regeneration ({@code ./gradlew generateJooq}) after a migration updates the published schema
 * automatically.
 *
 * <p>Field names are the raw database column names. Type is derived from the column's Java type:
 * {@code VARCHAR(36)} maps to {@code uuid} per the project's UUID storage convention; {@code JSONB}
 * maps to {@code array} when the column's DDL default is a JSON array ({@code '[]'::jsonb}), else
 * {@code object}. Override maps cover per-entity exceptions.
 */
@Component
@LogExecution
public class JooqTableSchemaResolver {

    private static final int UUID_COLUMN_LENGTH = 36;

    public List<QuerySchemaFieldDto> resolve(Table<?> table) {
        return resolve(table, Map.of(), Map.of());
    }

    /**
     * @param nameOverrides API field name per <em>column</em> name, for columns the naming
     *     conventions do not cover
     * @param typeOverrides field type per <em>column</em> name, for columns the type conventions do
     *     not cover
     */
    public List<QuerySchemaFieldDto> resolve(
            Table<?> table, Map<String, String> nameOverrides, Map<String, QueryFieldType> typeOverrides) {
        return Stream.of(table.fields())
                .map(column -> {
                    final String columnName = column.getName();
                    final String fieldName = nameOverrides.getOrDefault(columnName, columnName);
                    final QueryFieldType type =
                            typeOverrides.getOrDefault(columnName, fieldType(table.getName(), column));
                    return new QuerySchemaFieldDto(fieldName, type, fieldName);
                })
                .toList();
    }

    /**
     * Reverse of {@link #resolve}: maps each API field name back to the generated jOOQ {@link Field}
     * that backs it and its {@link QueryFieldType}, in DDL order. The model → jOOQ translation layer
     * uses this so a {@code FieldExpr} name resolves to exactly the column the client discovered via
     * the schema endpoint. Naming/typing conventions are shared with {@link #resolve} (no overrides).
     */
    public Map<String, QueryFieldBinding> bindings(Table<?> table) {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        for (final Field<?> column : table.fields()) {
            final String name = column.getName();
            bindings.put(name, new QueryFieldBinding(name, column, fieldType(table.getName(), column)));
        }
        return Collections.unmodifiableMap(bindings);
    }

    private static QueryFieldType fieldType(String tableName, Field<?> column) {
        final DataType<?> dataType = column.getDataType();
        final Class<?> javaType = column.getType();
        if (javaType == String.class) {
            return dataType.length() == UUID_COLUMN_LENGTH ? QueryFieldType.UUID : QueryFieldType.STRING;
        }
        if (javaType == Integer.class) {
            return QueryFieldType.INTEGER;
        }
        if (javaType == Long.class) {
            return QueryFieldType.LONG;
        }
        if (javaType == BigDecimal.class || javaType == Double.class || javaType == Float.class) {
            return QueryFieldType.DECIMAL;
        }
        if (javaType == Boolean.class) {
            return QueryFieldType.BOOLEAN;
        }
        if (javaType == JSONB.class) {
            return hasJsonArrayDefault(dataType) ? QueryFieldType.ARRAY : QueryFieldType.OBJECT;
        }
        throw new IllegalStateException("Column %s.%s has Java type %s with no %s mapping; add a type override"
                .formatted(tableName, column.getName(), javaType.getName(), QueryFieldType.class.getSimpleName()));
    }

    private static boolean hasJsonArrayDefault(DataType<?> dataType) {
        if (!dataType.defaulted()) {
            return false;
        }
        final String defaultValue =
                String.valueOf(dataType.defaultValue()).trim().toLowerCase(Locale.ROOT);
        return defaultValue.startsWith("'[");
    }
}
