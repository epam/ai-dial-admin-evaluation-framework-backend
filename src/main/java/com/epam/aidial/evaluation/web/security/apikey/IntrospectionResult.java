package com.epam.aidial.evaluation.web.security.apikey;

import java.util.List;

/**
 * Result of a DIAL Core {@code /v1/user/info} introspection.
 *
 * <p>Core returns one of two shapes depending on what minted the key:
 * <ul>
 *     <li><b>Project-key auth</b> ({@code fromProjectKey == true}) — response has {@code project};
 *         {@code principal} is the project name.</li>
 *     <li><b>JWT-rooted per-request key</b> ({@code fromProjectKey == false}) — response has
 *         {@code userClaims}; {@code principal} is extracted from the configured user-identity claim.</li>
 * </ul>
 */
public record IntrospectionResult(String principal, List<String> rawRoles, boolean fromProjectKey) {}
