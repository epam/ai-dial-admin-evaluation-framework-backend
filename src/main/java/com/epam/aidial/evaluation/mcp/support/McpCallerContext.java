package com.epam.aidial.evaluation.mcp.support;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Exposes the calling MCP client's identity to tool code. Tool bodies execute on the same HTTP
 * request thread as the security filter chain (Spring AI's servlet sync-server customizer sets
 * {@code immediateExecution(true)}, and the WebMVC streamable transport blocks on that thread), so
 * {@link SecurityContextHolder} and {@link AuthorizationTokenHolder} are populated exactly as they
 * are for a REST controller. No propagation machinery is added here; see
 * {@code docs/patterns/mcp-server.md}.
 *
 * <p>An {@code Api-Key} caller has a {@code String} principal (not a {@link Jwt}), so {@link #jwt()}
 * returns {@code null} for it; {@code AuthorizationHeaderInterceptor} still captures the
 * {@code Api-Key} header into {@link AuthorizationTokenHolder}, so {@link #callerCredential()}
 * returns an {@code API_KEY}-kind credential, {@code createdBy} resolves to the introspected
 * principal name via {@code AuthorResolver}, and Core-backed tools call DIAL Core with that
 * credential in its native header — exactly as the REST controllers do today.
 *
 * <p>In {@code config.rest.security.mode=none}, Spring Security's {@code HttpSecurity} still wires
 * an {@code AnonymousAuthenticationFilter} by default, so the context always holds an
 * {@link AnonymousAuthenticationToken} rather than a {@code null} {@code Authentication}.
 * {@link #authentication()} treats that placeholder the same as "no authentication" — it is not a
 * real caller identity — so callers can test {@code authentication() != null} to mean "a real
 * caller is present", matching REST's own token-based authentication.
 */
@Component
@LogExecution
public class McpCallerContext {

    public @Nullable Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof AnonymousAuthenticationToken ? null : authentication;
    }

    public @Nullable Jwt jwt() {
        Authentication authentication = authentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof Jwt jwt ? jwt : null;
    }

    public @Nullable CallerCredential callerCredential() {
        return AuthorizationTokenHolder.getCredential();
    }
}
