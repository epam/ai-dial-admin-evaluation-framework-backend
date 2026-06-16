package com.epam.aidial.evaluation.data.db.repository.sql;

import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import org.jooq.Field;
import org.jooq.impl.DSL;

@Value
@Builder
public class FilterFieldDefinition {

    Field<?> column;
    FilterFieldType type;
    Set<FilterOperator> operators;

    public static FilterFieldDefinition of(Field<?> column, FilterFieldType type, Set<FilterOperator> operators) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (operators == null || operators.isEmpty()) {
            throw new IllegalArgumentException("operators must not be empty");
        }
        return FilterFieldDefinition.builder()
                .column(column)
                .type(type)
                .operators(Set.copyOf(operators))
                .build();
    }

    public static FilterFieldDefinition of(String columnName, FilterFieldType type, Set<FilterOperator> operators) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("column must not be blank");
        }
        return of(DSL.field(columnName), type, operators);
    }
}
