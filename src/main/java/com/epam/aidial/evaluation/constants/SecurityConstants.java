package com.epam.aidial.evaluation.constants;

/**
 * Non-configurable security-related constants (e.g. correlation ID format).
 */
public final class SecurityConstants {

    /** Minimum length of correlation ID (X-Correlation-Id header). */
    public static final int CORRELATION_ID_MIN_LENGTH = 16;
    /** Maximum length of correlation ID (X-Correlation-Id header). */
    public static final int CORRELATION_ID_MAX_LENGTH = 32;

    /**
     * Static path of the throwaway query-DSL demo console
     * ({@code src/main/resources/static/query-dsl-demo.html}), whitelisted together with Swagger UI.
     */
    public static final String QUERY_DSL_DEMO_PAGE = "/query-dsl-demo.html";

    private SecurityConstants() {}
}
