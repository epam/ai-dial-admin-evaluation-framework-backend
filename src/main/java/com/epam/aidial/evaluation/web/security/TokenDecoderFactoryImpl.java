package com.epam.aidial.evaluation.web.security;

import com.epam.aidial.evaluation.configuration.properties.security.JwtProvidersProperties;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@RequiredArgsConstructor
public class TokenDecoderFactoryImpl implements TokenDecoderFactory {

    private final Map<String, JwtProvidersProperties.ProviderConfig> providers;
    private final IssuerToDecoderMapFactory issuerToDecoderMapFactory;

    public JwtDecoder createJwtDecoder() {
        final var issuerToDecoderMap = new HashMap<String, JwtDecoder>();
        for (final JwtProvidersProperties.ProviderConfig config : providers.values()) {
            var jwkSetUri = config.getJwkSetUri();
            final var jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            issuerToDecoderMap.putAll(issuerToDecoderMapFactory.createIssuerToDecoderMap(jwtDecoder, config));
        }
        return new MultiIssuerJwtDecoder(issuerToDecoderMap);
    }
}
