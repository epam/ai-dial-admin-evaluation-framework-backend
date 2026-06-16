package com.epam.aidial.evaluation.data.db.repository.sql;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.model.pagination.SortKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.Field;
import org.jooq.SortField;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class OrderByBuilder {

    public List<SortField<?>> build(List<SortKey> sortKeys, SortSpec sortSpec) {
        if (sortSpec == null
                || sortSpec.getAllowedFields() == null
                || sortSpec.getAllowedFields().isEmpty()) {
            throw new IllegalArgumentException("sortSpec must define allowed fields");
        }

        List<SortKey> requested = (sortKeys == null || sortKeys.isEmpty()) ? sortSpec.getDefaultSort() : sortKeys;
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("Sort keys must not be empty");
        }

        Map<String, Field<?>> allowedFields = sortSpec.getAllowedFields();
        List<SortKey> normalized = new ArrayList<>(requested.size() + 1);
        for (SortKey sortKey : requested) {
            if (sortKey == null
                    || sortKey.getField() == null
                    || sortKey.getField().isBlank()) {
                throw new IllegalArgumentException("Sort field must not be blank");
            }
            String field = sortKey.getField().trim();
            Field<?> column = allowedFields.get(field);
            if (column == null) {
                throw new IllegalArgumentException("Unknown sort field: " + field);
            }
            PageRequest.SortDirection direction =
                    sortKey.getDirection() != null ? sortKey.getDirection() : PageRequest.SortDirection.ASC;
            normalized.add(SortKey.builder().field(field).direction(direction).build());
        }

        boolean hasId = normalized.stream().anyMatch(s -> Objects.equals("id", s.getField()));
        if (!hasId && allowedFields.containsKey("id")) {
            normalized.add(SortKey.builder()
                    .field("id")
                    .direction(PageRequest.SortDirection.ASC)
                    .build());
        }

        List<SortField<?>> result = new ArrayList<>(normalized.size());
        for (SortKey sortKey : normalized) {
            Field<?> column = allowedFields.get(sortKey.getField());
            if (sortKey.getDirection() == PageRequest.SortDirection.DESC) {
                result.add(column.desc());
            } else {
                result.add(column.asc());
            }
        }
        return result;
    }
}
