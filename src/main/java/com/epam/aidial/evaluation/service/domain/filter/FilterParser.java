package com.epam.aidial.evaluation.service.domain.filter;

import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class FilterParser {

    /**
     * Parses repeatable {@code filter} parameters of the form {@code <field>:<operator>:<value>}.
     *
     * @throws FilterValidationException when a filter param is malformed or an operator is unsupported.
     */
    public List<FilterCondition> parse(List<String> filterParams) {
        if (filterParams == null || filterParams.isEmpty()) {
            return List.of();
        }

        List<FilterCondition> result = new ArrayList<>(filterParams.size());
        for (String raw : filterParams) {
            result.add(parseSingle(raw));
        }
        return List.copyOf(result);
    }

    private static FilterCondition parseSingle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalidFilter(raw, "filter parameter must not be blank", null, null, null);
        }

        int firstSeparator = raw.indexOf(':');
        int secondSeparator = firstSeparator < 0 ? -1 : raw.indexOf(':', firstSeparator + 1);
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1 || secondSeparator >= raw.length() - 1) {
            throw invalidFilter(raw, "expected <field>:<operator>:<value>", null, null, null);
        }

        String field = raw.substring(0, firstSeparator).trim();
        String operatorRaw = raw.substring(firstSeparator + 1, secondSeparator).trim();
        String valueRaw = raw.substring(secondSeparator + 1);

        if (field.isBlank()) {
            throw invalidFilter(raw, "field must not be blank", field, operatorRaw, valueRaw);
        }
        if (operatorRaw.isBlank()) {
            throw invalidFilter(raw, "operator must not be blank", field, operatorRaw, valueRaw);
        }

        FilterOperator operator = parseOperator(operatorRaw, raw, field, valueRaw);

        if (operator == FilterOperator.IN) {
            return parseInCondition(raw, field, operator, valueRaw);
        }

        String decodedValue = decodeValue(valueRaw, raw, field, operatorRaw);
        if (decodedValue.isBlank()) {
            throw invalidFilter(raw, "value must not be blank", field, operatorRaw, decodedValue);
        }

        return FilterCondition.builder()
                .field(field)
                .operator(operator)
                .rawValue(decodedValue)
                .build();
    }

    private static FilterCondition parseInCondition(
            String raw, String field, FilterOperator operator, String valueRaw) {
        String[] parts = valueRaw.split(",", -1);
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String decoded = decodeValue(part, raw, field, operator.name());
            if (decoded.isBlank()) {
                throw invalidFilter(raw, "IN value elements must not be blank", field, operator.name(), valueRaw);
            }
            values.add(decoded);
        }
        if (values.isEmpty()) {
            throw invalidFilter(raw, "IN value must not be empty", field, operator.name(), valueRaw);
        }
        List<String> immutableValues = List.copyOf(values);
        return FilterCondition.builder()
                .field(field)
                .operator(operator)
                .rawValue(valueRaw)
                .parsedValue(immutableValues)
                .build();
    }

    private static final Map<String, FilterOperator> OPERATOR_ALIASES = Map.of(
            "GTE", FilterOperator.GE,
            "LTE", FilterOperator.LE);

    private static FilterOperator parseOperator(String operatorRaw, String rawFilter, String field, String valueRaw) {
        String normalized = operatorRaw.toUpperCase(Locale.ROOT);
        FilterOperator alias = OPERATOR_ALIASES.get(normalized);
        if (alias != null) {
            return alias;
        }
        try {
            return FilterOperator.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw invalidFilter(rawFilter, "unsupported operator '" + operatorRaw + "'", field, operatorRaw, valueRaw);
        }
    }

    private static String decodeValue(String valueRaw, String rawFilter, String field, String operatorRaw) {
        try {
            return URLDecoder.decode(valueRaw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw invalidFilter(rawFilter, "invalid URL encoding", field, operatorRaw, valueRaw);
        }
    }

    private static FilterValidationException invalidFilter(
            String rawFilter, String reason, String field, String operator, String value) {
        return new FilterValidationException(
                "Invalid filter: " + reason, buildDetails(rawFilter, field, operator, value, reason));
    }

    private static Map<String, Object> buildDetails(
            String rawFilter, String field, String operator, String value, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (rawFilter != null) {
            details.put("filter", rawFilter);
        }
        if (field != null && !field.isBlank()) {
            details.put("field", field);
        }
        if (operator != null && !operator.isBlank()) {
            details.put("operator", operator);
        }
        if (value != null) {
            details.put("value", value);
        }
        if (reason != null) {
            details.put("reason", reason);
        }
        return details;
    }
}
