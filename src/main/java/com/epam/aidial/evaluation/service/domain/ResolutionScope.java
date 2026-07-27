package com.epam.aidial.evaluation.service.domain;

import java.util.Map;

/**
 * Everything a template variable can resolve against, in one extensible carrier: the test case's
 * {@code data} map, and — for a multi-request chain — the accumulating map of response column values
 * extracted by <b>earlier</b> requests of the same test-case run.
 *
 * <p>The carrier exists so a new resolution source can be added without another parameter threaded
 * through every private method of {@link ResolvedRequestService}. Both maps are read-only inputs; nothing
 * writes back through this type.
 */
public record ResolutionScope(Map<String, Object> data, Map<String, Object> responseValues) {

    private static final ResolutionScope EMPTY = new ResolutionScope(Map.of(), Map.of());

    /** A scope with test-case data only and no chain values — every single-request resolution. */
    public static ResolutionScope ofData(Map<String, Object> data) {
        return new ResolutionScope(data != null ? data : Map.of(), Map.of());
    }

    /** A chain-request scope: test-case data plus the values extracted by earlier chain requests. */
    public static ResolutionScope of(Map<String, Object> data, Map<String, Object> responseValues) {
        return new ResolutionScope(data != null ? data : Map.of(), responseValues != null ? responseValues : Map.of());
    }

    public static ResolutionScope empty() {
        return EMPTY;
    }

    public Map<String, Object> safeData() {
        return data != null ? data : Map.of();
    }

    public Map<String, Object> safeResponseValues() {
        return responseValues != null ? responseValues : Map.of();
    }
}
