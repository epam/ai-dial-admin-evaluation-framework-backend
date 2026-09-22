package com.epam.aidial.evaluation.runner.util;

/**
 * The kind of caller credential captured for the current request, deciding which outbound HTTP
 * header {@link CallerCredential} is forwarded as.
 */
public enum CredentialKind {
    BEARER,
    API_KEY
}
