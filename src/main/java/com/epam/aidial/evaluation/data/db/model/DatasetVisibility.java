package com.epam.aidial.evaluation.data.db.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

public enum DatasetVisibility {
    PUBLIC("PUBLIC"),
    PRIVATE("PRIVATE");

    private final String value;

    DatasetVisibility(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DatasetVisibility fromValue(String value) {
        return Arrays.stream(values())
                .filter(t -> t.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Invalid dataset visibility: " + value + ". Valid values: "
                                + Arrays.stream(values())
                                        .map(DatasetVisibility::getValue)
                                        .collect(Collectors.joining(", "))));
    }
}
