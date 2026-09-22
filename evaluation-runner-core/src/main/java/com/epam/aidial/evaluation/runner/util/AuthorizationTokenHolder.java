package com.epam.aidial.evaluation.runner.util;

import lombok.experimental.UtilityClass;

/**
 * Thread-local storage for the current request's caller credential (bearer token or API key).
 * Used to propagate the caller's credential to outbound services (e.g. DIAL Core) when making HTTP
 * calls. The holder stores a {@link CallerCredential}; any consumer that forms an outbound header
 * must read {@link #getCredential()} (never just a raw value), so a bearer token can never be sent
 * as an API key (or vice versa).
 */
@UtilityClass
public class AuthorizationTokenHolder {

    private static final ThreadLocal<CallerCredential> CREDENTIAL_HOLDER = new ThreadLocal<>();

    public static CallerCredential getCredential() {
        return CREDENTIAL_HOLDER.get();
    }

    /** {@code null} clears the current credential. */
    public static void setCredential(CallerCredential credential) {
        if (credential == null) {
            CREDENTIAL_HOLDER.remove();
        } else {
            CREDENTIAL_HOLDER.set(credential);
        }
    }

    public static void clearToken() {
        CREDENTIAL_HOLDER.remove();
    }
}
