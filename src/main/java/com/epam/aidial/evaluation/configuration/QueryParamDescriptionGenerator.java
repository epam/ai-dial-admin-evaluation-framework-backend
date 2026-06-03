package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterFieldDefinition;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterFieldType;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterSpec;
import com.epam.aidial.evaluation.data.db.repository.sql.SortSpec;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class QueryParamDescriptionGenerator {

    private static final Set<String> TIMESTAMP_FIELDS = Set.of("createdAt", "updatedAt", "startedAt", "completedAt");

    private QueryParamDescriptionGenerator() {}

    static String generateFilterDescription(FilterSpec filterSpec) {
        StringBuilder sb = new StringBuilder();
        sb.append("Format: `field:operator:value` (repeatable, max 32). ");
        sb.append("Multiple filters are combined with AND logic.\n\n");
        sb.append("| Field | Type | Operators | Example |\n");
        sb.append("|-------|------|-----------|--------|\n");

        new TreeMap<>(filterSpec.getAllowedFields()).forEach((fieldName, definition) -> {
            String typeLabel = resolveTypeLabel(fieldName, definition.getType());
            String operators = definition.getOperators().stream()
                    .sorted()
                    .map(op -> op.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
            String example = generateFieldExample(fieldName, definition);
            sb.append("| ")
                    .append(fieldName)
                    .append(" | ")
                    .append(typeLabel)
                    .append(" | ")
                    .append(operators)
                    .append(" | `")
                    .append(example)
                    .append("` |\n");
        });

        return sb.toString().trim();
    }

    static String generateFilterExample(FilterSpec filterSpec) {
        return filterSpec.getAllowedFields().entrySet().stream()
                .filter(entry -> entry.getValue().getType() == FilterFieldType.STRING
                        && entry.getValue().getOperators().contains(FilterOperator.CO))
                .map(entry -> entry.getKey() + ":co:test")
                .sorted()
                .findFirst()
                .orElseGet(() -> {
                    var entry = new TreeMap<>(filterSpec.getAllowedFields()).firstEntry();
                    FilterOperator op = selectPreferredOperator(entry.getKey(), entry.getValue());
                    String value =
                            resolveExampleValue(entry.getKey(), entry.getValue().getType());
                    return entry.getKey() + ":" + op.name().toLowerCase(Locale.ROOT) + ":" + value;
                });
    }

    static String generateSortDescription(SortSpec sortSpec) {
        String defaultSort = sortSpec.getDefaultSort().stream()
                .map(sk -> sk.getField() + "," + sk.getDirection().name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining("; "));

        String fields = sortSpec.getAllowedFields().keySet().stream().sorted().collect(Collectors.joining(", "));

        return "Format: `field[,asc|desc]` (repeatable, max 32). Default: `" + defaultSort + "`.\n\n"
                + "Sortable fields: " + fields;
    }

    static String generateSortExample(SortSpec sortSpec) {
        return sortSpec.getDefaultSort().stream()
                .map(sk -> sk.getField() + "," + sk.getDirection().name().toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse("createdAt,desc");
    }

    static String generatePageDescription() {
        return "0-indexed page number. Default: 0.";
    }

    static String generateSizeDescription(int defaultSize, int maxSize) {
        return "Page size. Default: " + defaultSize + ", max: " + maxSize + ".";
    }

    static String generateCursorDescription() {
        return "Opaque cursor from `nextCursor` in a previous response. Omit for the first page.";
    }

    private static String resolveTypeLabel(String fieldName, FilterFieldType type) {
        return switch (type) {
            case STRING -> "string";
            case LONG -> TIMESTAMP_FIELDS.contains(fieldName) ? "timestamp (epoch ms)" : "integer";
            case BOOLEAN -> "boolean (true/false)";
            case UUID -> "uuid";
            case JSONB_STRING -> "jsonb string";
            case JSONB_NUMERIC -> "jsonb numeric (two-level path: metricName.outputName)";
        };
    }

    private static String generateFieldExample(String fieldName, FilterFieldDefinition definition) {
        FilterOperator preferredOp = selectPreferredOperator(fieldName, definition);
        String op = preferredOp.name().toLowerCase(Locale.ROOT);
        String value = resolveExampleValue(fieldName, definition.getType());
        return fieldName + ":" + op + ":" + value;
    }

    private static FilterOperator selectPreferredOperator(String fieldName, FilterFieldDefinition definition) {
        Set<FilterOperator> operators = definition.getOperators();
        FilterFieldType type = definition.getType();

        if ((type == FilterFieldType.STRING || type == FilterFieldType.JSONB_STRING)
                && operators.contains(FilterOperator.CO)) {
            return FilterOperator.CO;
        }
        if (type == FilterFieldType.LONG
                && TIMESTAMP_FIELDS.contains(fieldName)
                && operators.contains(FilterOperator.GT)) {
            return FilterOperator.GT;
        }
        if (operators.contains(FilterOperator.EQ)) {
            return FilterOperator.EQ;
        }
        return operators.stream().sorted().findFirst().orElseThrow();
    }

    private static String resolveExampleValue(String fieldName, FilterFieldType type) {
        return switch (type) {
            case STRING -> "test";
            case LONG -> TIMESTAMP_FIELDS.contains(fieldName) ? "1700000000000" : "1";
            case BOOLEAN -> "true";
            case UUID -> "550e8400-e29b-41d4-a716-446655440000";
            case JSONB_STRING -> "value";
            case JSONB_NUMERIC -> "0.95";
        };
    }
}
