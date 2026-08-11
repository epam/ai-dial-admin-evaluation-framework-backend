package com.epam.aidial.evaluation.web.security.apikey;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * Authentication token produced from a validated DIAL API-Key introspection result. The
 * principal is the resolved identity string — the DIAL Core project name for a project-key
 * caller, or the configured user-identity claim value for a JWT-rooted per-request-key caller.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String principal;

    public ApiKeyAuthenticationToken(String principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal;
    }
}
