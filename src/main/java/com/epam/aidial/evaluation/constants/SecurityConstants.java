package com.epam.aidial.evaluation.constants;

/**
 * Non-configurable security-related constants (e.g. correlation ID format).
 */
public final class SecurityConstants {

    public static final String CORRELATION_ID_HEADER_NAME = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "_correlation_id";

    /** Minimum length of correlation ID (X-Correlation-Id header). */
    public static final int CORRELATION_ID_MIN_LENGTH = 16;
    /** Maximum length of correlation ID (X-Correlation-Id header). */
    public static final int CORRELATION_ID_MAX_LENGTH = 32;

    private SecurityConstants() {}
}
