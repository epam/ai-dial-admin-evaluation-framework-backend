package com.epam.aidial.evaluation.web.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@Data
public class MultiPathGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String DEFAULT_AUTHORITY_PREFIX = "SCOPE_";
    private String authorityPrefix = DEFAULT_AUTHORITY_PREFIX;

    private List<String> authoritiesPaths;

    @NotNull
    @Override
    public List<GrantedAuthority> convert(@NotNull Jwt token) {
        var authorities = getAuthorities(token);
        return authorities.stream()
                .map(authority -> new SimpleGrantedAuthority(authorityPrefix + authority))
                .collect(Collectors.toList());
    }

    private Set<String> getAuthorities(Jwt token) {
        return ClaimPathExtractor.extractRoles(authoritiesPaths, token.getClaims());
    }
}
