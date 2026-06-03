package com.epam.aidial.evaluation.service.domain.exception;

import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;

public class TypeMismatchException extends RuntimeException {

    private static final int VALUE_PREVIEW_MAX_LENGTH = 80;

    public TypeMismatchException(SchemaFieldType expected, String actualTypeLabel) {
        super(buildMessage(expected, actualTypeLabel));
    }

    public TypeMismatchException(SchemaFieldType expected, String actualTypeLabel, Object actualValue, String suffix) {
        super(buildMessageWithValue(expected, actualTypeLabel, actualValue, suffix));
    }

    private static String buildMessage(SchemaFieldType expected, String actualTypeLabel) {
        return "Type mismatch: expected " + expected.name() + ", got " + actualTypeLabel;
    }

    private static String buildMessageWithValue(
            SchemaFieldType expected, String actualTypeLabel, Object actualValue, String suffix) {
        return buildMessage(expected, actualTypeLabel) + " (\"" + truncate(actualValue) + "\")" + " — " + suffix;
    }

    private static String truncate(Object value) {
        String str = String.valueOf(value);
        if (str.length() <= VALUE_PREVIEW_MAX_LENGTH) {
            return str;
        }
        return str.substring(0, VALUE_PREVIEW_MAX_LENGTH);
    }
}
