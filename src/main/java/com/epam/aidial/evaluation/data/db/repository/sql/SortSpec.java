package com.epam.aidial.evaluation.data.db.repository.sql;

import com.epam.aidial.evaluation.data.db.model.pagination.SortKey;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import org.jooq.Field;

@Value
@Builder
public class SortSpec {

    Map<String, Field<?>> allowedFields;
    List<SortKey> defaultSort;

    public static SortSpec of(Map<String, Field<?>> allowedFields, List<SortKey> defaultSort) {
        if (allowedFields == null || allowedFields.isEmpty()) {
            throw new IllegalArgumentException("allowedFields must not be empty");
        }
        if (defaultSort == null || defaultSort.isEmpty()) {
            throw new IllegalArgumentException("defaultSort must not be empty");
        }

        for (SortKey sortKey : defaultSort) {
            if (sortKey == null
                    || sortKey.getField() == null
                    || sortKey.getField().isBlank()) {
                throw new IllegalArgumentException("defaultSort entries must have non-blank fields");
            }
            if (!allowedFields.containsKey(sortKey.getField().trim())) {
                throw new IllegalArgumentException("defaultSort contains unknown field: " + sortKey.getField());
            }
        }

        return SortSpec.builder()
                .allowedFields(Map.copyOf(allowedFields))
                .defaultSort(List.copyOf(defaultSort))
                .build();
    }
}
