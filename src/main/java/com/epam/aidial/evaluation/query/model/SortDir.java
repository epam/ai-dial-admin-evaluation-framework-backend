package com.epam.aidial.evaluation.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Sort direction (§6). Wire values {@code asc}/{@code desc}. */
public enum SortDir {
    ASC("asc"),
    DESC("desc");

    private final String code;

    SortDir(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
