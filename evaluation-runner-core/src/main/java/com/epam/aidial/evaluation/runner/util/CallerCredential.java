package com.epam.aidial.evaluation.runner.util;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;

/**
 * The current caller's credential and its {@link CredentialKind}, captured from the inbound request
 * (see {@code AuthorizationHeaderInterceptor}) and propagated across thread hops via
 * {@link AuthorizationTokenHolder} / {@link TokenPropagationHelper}.
 *
 * <p>Use {@link #bearer(String)} / {@link #apiKey(String)} to construct an instance; both return
 * {@code null} for a {@code null}/blank value rather than throwing, so a missing header never fails a
 * request. The canonical constructor rejects a blank {@code value} or {@code null} {@code kind}, so an
 * instance can never render a header such as {@code "Bearer null"}: "no credential" is always
 * {@code null}, never a hollow instance. Outbound HTTP clients use {@link #headerName()} / {@link #headerValue()} to set exactly one
 * header ({@code Authorization: Bearer} or {@value #API_KEY_HEADER}).
 */
public record CallerCredential(String value, CredentialKind kind) {

    /** The HTTP header name DIAL Core (and any Core-compatible peer) accepts an API key under. */
    public static final String API_KEY_HEADER = "Api-Key";

    private static final String BEARER_PREFIX = "Bearer ";

    public CallerCredential {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Credential value must not be blank; use null for 'no credential'");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Credential kind must not be null");
        }
    }

    public static CallerCredential bearer(String value) {
        return StringUtils.isBlank(value) ? null : new CallerCredential(value, CredentialKind.BEARER);
    }

    public static CallerCredential apiKey(String value) {
        return StringUtils.isBlank(value) ? null : new CallerCredential(value, CredentialKind.API_KEY);
    }

    /** The outbound HTTP header name to carry this credential under, chosen from {@link #kind()}. */
    public String headerName() {
        return switch (kind) {
            case BEARER -> HttpHeaders.AUTHORIZATION;
            case API_KEY -> API_KEY_HEADER;
        };
    }

    /** The outbound HTTP header value to carry this credential under, chosen from {@link #kind()}. */
    public String headerValue() {
        return switch (kind) {
            case BEARER -> BEARER_PREFIX + value;
            case API_KEY -> value;
        };
    }

    /** Masks {@code value} so it never leaks into logs via a bare {@code toString()}. */
    @Override
    public String toString() {
        return "CallerCredential[kind=" + kind + ", value=***]";
    }
}
