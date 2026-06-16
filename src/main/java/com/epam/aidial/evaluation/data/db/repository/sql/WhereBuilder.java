package com.epam.aidial.evaluation.data.db.repository.sql;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.data.db.repository.sql.json.JsonPathAccessor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class WhereBuilder {

    private final JsonPathAccessor jsonPathAccessor;

    /**
     * Builds a jOOQ {@link Condition} for the provided filter conditions.
     *
     * @throws InvalidFilterException when a filter field or operator is not allowed or a value cannot be parsed.
     */
    public Condition build(List<FilterCondition> conditions, FilterSpec filterSpec) {
        if (filterSpec == null
                || filterSpec.getAllowedFields() == null
                || filterSpec.getAllowedFields().isEmpty()) {
            throw new IllegalArgumentException("filterSpec must define allowed fields");
        }
        if (conditions == null || conditions.isEmpty()) {
            return DSL.trueCondition();
        }

        Map<String, FilterFieldDefinition> allowedFields = filterSpec.getAllowedFields();
        List<Condition> predicates = new ArrayList<>(conditions.size());

        for (FilterCondition condition : conditions) {
            if (condition == null) {
                throw new IllegalArgumentException("Filter condition must not be null");
            }
            String field = normalizeField(condition);

            // Try exact match first (supports dotted field names like executionInfo.retryCount)
            // then fall back to JSONB dot-notation splitting (e.g. testCaseData.someKey)
            String jsonbKey = null;
            String lookupField = field;
            FilterFieldDefinition definition = allowedFields.get(field);

            if (definition == null && field.contains(".")) {
                int dotIndex = field.indexOf('.');
                lookupField = field.substring(0, dotIndex);
                jsonbKey = field.substring(dotIndex + 1);

                if (jsonbKey.isBlank()) {
                    throw invalidFilter(
                            "JSONB key must not be empty", field, condition.getOperator(), condition.getRawValue());
                }
                definition = allowedFields.get(lookupField);
            }
            if (definition == null) {
                throw invalidFilter(
                        "unknown field '" + lookupField + "'", field, condition.getOperator(), condition.getRawValue());
            }

            // Validate JSONB path access type
            String jsonbKey2 = null;
            if (jsonbKey != null) {
                if (definition.getType() == FilterFieldType.JSONB_NUMERIC) {
                    // Two-level path required: e.g. metricValues.Accuracy.score
                    if (!jsonbKey.contains(".")) {
                        throw invalidFilter(
                                "JSONB_NUMERIC requires two-level path (e.g. metricName.outputName)",
                                field,
                                condition.getOperator(),
                                condition.getRawValue());
                    }
                    int innerDot = jsonbKey.indexOf('.');
                    jsonbKey2 = jsonbKey.substring(innerDot + 1);
                    jsonbKey = jsonbKey.substring(0, innerDot);
                    if (jsonbKey2.contains(".")) {
                        throw invalidFilter(
                                "nested JSONB paths deeper than two levels not supported",
                                field,
                                condition.getOperator(),
                                condition.getRawValue());
                    }
                } else if (definition.getType() == FilterFieldType.JSONB_STRING) {
                    if (jsonbKey.contains(".")) {
                        throw invalidFilter(
                                "nested JSONB paths not supported",
                                field,
                                condition.getOperator(),
                                condition.getRawValue());
                    }
                } else {
                    throw invalidFilter(
                            "field '" + lookupField + "' does not support JSONB path access",
                            field,
                            condition.getOperator(),
                            condition.getRawValue());
                }
            }

            FilterOperator operator = requireOperator(condition, field);
            validateOperator(definition, operator, field);

            String rawValue = condition.getRawValue();
            if (rawValue == null) {
                throw invalidFilter("value must not be null", field, operator, null);
            }

            if (operator == FilterOperator.IN) {
                @SuppressWarnings("unchecked")
                List<String> inValues = (List<String>) condition.getParsedValue();
                if (definition.getType() == FilterFieldType.UUID) {
                    inValues = validateAndNormalizeUuidList(inValues, field);
                }
                condition.setParsedValue(inValues);
                predicates.add(buildInCondition(definition.getColumn(), inValues));
                continue;
            }

            Object parsedValue = parseValue(definition.getType(), rawValue, field);
            condition.setParsedValue(parsedValue);

            if (jsonbKey != null) {
                if (jsonbKey2 != null) {
                    // JSONB_NUMERIC two-level path
                    @SuppressWarnings("unchecked")
                    Field<JSONB> jsonbColumn = (Field<JSONB>) definition.getColumn();
                    Field<BigDecimal> numericField =
                            jsonPathAccessor.jsonbAtAsNumeric(jsonbColumn, DSL.val(jsonbKey), DSL.val(jsonbKey2));
                    predicates.add(buildNumericCondition(numericField, operator, (BigDecimal) parsedValue, field));
                } else {
                    @SuppressWarnings("unchecked")
                    Field<JSONB> jsonbColumn = (Field<JSONB>) definition.getColumn();
                    Field<String> textField = jsonPathAccessor.jsonbAtAsText(jsonbColumn, DSL.val(jsonbKey));
                    predicates.add(buildJsonbStringCondition(
                            textField, operator, (String) parsedValue, definition.getType(), field));
                }
            } else {
                predicates.add(buildCondition(definition.getColumn(), operator, parsedValue, definition.getType()));
            }
        }

        return DSL.and(predicates);
    }

    private static String normalizeField(FilterCondition condition) {
        String field = condition.getField();
        if (field == null || field.isBlank()) {
            throw invalidFilter("field must not be blank", null, condition.getOperator(), condition.getRawValue());
        }
        return field.trim();
    }

    private static FilterOperator requireOperator(FilterCondition condition, String field) {
        if (condition.getOperator() == null) {
            throw invalidFilter("operator must not be null", field, null, condition.getRawValue());
        }
        return condition.getOperator();
    }

    private static void validateOperator(FilterFieldDefinition definition, FilterOperator operator, String field) {
        Set<FilterOperator> allowedOperators = definition.getOperators();
        if (allowedOperators == null || allowedOperators.isEmpty()) {
            throw new IllegalArgumentException("No operators are configured for field: " + field);
        }
        if (!allowedOperators.contains(operator)) {
            throw invalidFilter(
                    "operator '" + operator + "' is not allowed for field '" + field + "'", field, operator, null);
        }
        if (operator == FilterOperator.CO
                && definition.getType() != FilterFieldType.STRING
                && definition.getType() != FilterFieldType.JSONB_STRING) {
            throw invalidFilter(
                    "operator 'CO' is only allowed for STRING or JSONB_STRING fields", field, operator, null);
        }
        if (operator == FilterOperator.IN
                && definition.getType() != FilterFieldType.STRING
                && definition.getType() != FilterFieldType.UUID) {
            throw invalidFilter("operator 'IN' is only allowed for STRING or UUID fields", field, operator, null);
        }
    }

    private static Object parseValue(FilterFieldType type, String rawValue, String field) {
        return switch (type) {
            case STRING, JSONB_STRING -> rawValue;
            case LONG -> parseLong(rawValue, field);
            case BOOLEAN -> parseBoolean(rawValue, field);
            case UUID -> parseUuid(rawValue, field);
            case JSONB_NUMERIC -> parseBigDecimal(rawValue, field);
        };
    }

    private static Long parseLong(String rawValue, String field) {
        try {
            return Long.parseLong(rawValue.trim());
        } catch (NumberFormatException ex) {
            throw invalidFilter("invalid long value for field '" + field + "'", field, null, rawValue);
        }
    }

    private static Boolean parseBoolean(String rawValue, String field) {
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized)) {
            return Boolean.FALSE;
        }
        throw invalidFilter("invalid boolean value for field '" + field + "'", field, null, rawValue);
    }

    private static String parseUuid(String rawValue, String field) {
        try {
            return UUID.fromString(rawValue.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw invalidFilter("invalid UUID value for field '" + field + "'", field, null, rawValue);
        }
    }

    private static BigDecimal parseBigDecimal(String rawValue, String field) {
        try {
            return new BigDecimal(rawValue.trim());
        } catch (NumberFormatException ex) {
            throw invalidFilter("invalid numeric value for field '" + field + "'", field, null, rawValue);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Condition buildInCondition(Field<?> column, List<String> inValues) {
        return ((Field) column).in(inValues);
    }

    private static List<String> validateAndNormalizeUuidList(List<String> rawValues, String field) {
        List<String> validated = new ArrayList<>(rawValues.size());
        for (String value : rawValues) {
            validated.add(parseUuid(value, field));
        }
        return List.copyOf(validated);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Condition buildCondition(
            Field<?> column, FilterOperator operator, Object parsedValue, FilterFieldType type) {
        boolean caseInsensitive =
                type == FilterFieldType.STRING && (operator == FilterOperator.EQ || operator == FilterOperator.NE);
        Field<String> strField = (Field<String>) column;
        Field stringVal = DSL.val(parsedValue instanceof String s ? s : String.valueOf(parsedValue));
        return switch (operator) {
            case EQ ->
                caseInsensitive ? DSL.lower(strField).eq(DSL.lower(stringVal)) : ((Field) column).eq(parsedValue);
            case NE ->
                caseInsensitive ? DSL.lower(strField).ne(DSL.lower(stringVal)) : ((Field) column).ne(parsedValue);
            case CO -> strField.likeIgnoreCase("%" + parsedValue + "%");
            case GT -> ((Field) column).gt(parsedValue);
            case GE -> ((Field) column).ge(parsedValue);
            case LT -> ((Field) column).lt(parsedValue);
            case LE -> ((Field) column).le(parsedValue);
            case IN -> throw new IllegalStateException("IN operator must be handled before buildCondition");
        };
    }

    private static Condition buildJsonbStringCondition(
            Field<String> textField, FilterOperator operator, String value, FilterFieldType type, String field) {
        boolean caseInsensitive = type == FilterFieldType.JSONB_STRING
                && (operator == FilterOperator.EQ || operator == FilterOperator.NE);
        return switch (operator) {
            case EQ -> caseInsensitive ? DSL.lower(textField).eq(DSL.lower(DSL.val(value))) : textField.eq(value);
            case NE -> caseInsensitive ? DSL.lower(textField).ne(DSL.lower(DSL.val(value))) : textField.ne(value);
            case CO -> textField.likeIgnoreCase("%" + value + "%");
            default ->
                throw invalidFilter(
                        "operator '" + operator + "' is not supported for JSONB fields", null, operator, null);
        };
    }

    private static Condition buildNumericCondition(
            Field<BigDecimal> numericField, FilterOperator operator, BigDecimal value, String field) {
        return switch (operator) {
            case EQ -> numericField.eq(value);
            case NE -> numericField.ne(value);
            case GT -> numericField.gt(value);
            case GE -> numericField.ge(value);
            case LT -> numericField.lt(value);
            case LE -> numericField.le(value);
            default ->
                throw invalidFilter(
                        "operator '" + operator + "' is not supported for JSONB_NUMERIC fields", null, operator, null);
        };
    }

    private static InvalidFilterException invalidFilter(
            String reason, String field, FilterOperator operator, String value) {
        return new InvalidFilterException("Invalid filter: " + reason, buildDetails(field, operator, value, reason));
    }

    private static Map<String, Object> buildDetails(
            String field, FilterOperator operator, String value, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (field != null && !field.isBlank()) {
            details.put("field", field);
        }
        if (operator != null) {
            details.put("operator", operator.name());
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
