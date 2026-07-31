package com.epam.aidial.evaluation.runner.util;

import lombok.experimental.UtilityClass;

/**
 * Thread-local storage for the JWT token from the current request.
 * Used to propagate the user's token to outbound services (e.g. DIAL Core) when making HTTP calls.
 */
@UtilityClass
public class AuthorizationTokenHolder {

    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();

    public static String getToken() {
        return TOKEN_HOLDER.get();
    }

    public static void setToken(String token) {
        TOKEN_HOLDER.set(token);
    }

    public static void clearToken() {
        TOKEN_HOLDER.remove();
    }
}
