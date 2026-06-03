package com.epam.aidial.evaluation.data.db.repository.sql;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class FilterSpec {

    Map<String, FilterFieldDefinition> allowedFields;

    public static FilterSpec of(Map<String, FilterFieldDefinition> allowedFields) {
        if (allowedFields == null || allowedFields.isEmpty()) {
            throw new IllegalArgumentException("allowedFields must not be empty");
        }
        return FilterSpec.builder().allowedFields(Map.copyOf(allowedFields)).build();
    }
}
