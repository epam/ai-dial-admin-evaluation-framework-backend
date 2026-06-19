package com.epam.aidial.evaluation.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.epam.aidial.evaluation.configuration.properties.security.JwtProvidersProperties.ProviderConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtProviderUtils")
class JwtProviderUtilsTest {

    private final JwtProviderUtils utils = new JwtProviderUtils();

    @Test
    @DisplayName("getAcceptedIssuers builds Azure issuers when issuer is a non-absolute tenant id")
    void getAcceptedIssuersBuildsAzureIssuersForNonAbsoluteIssuer() {
        final var config = new ProviderConfig();
        config.setIssuer("common");
        config.setAliases(List.of("login.microsoftonline.com"));

        assertThat(utils.getAcceptedIssuers(config))
                .containsExactlyInAnyOrder(
                        "https://login.microsoftonline.com/common/", "https://login.microsoftonline.com/common/v2.0");
    }

    @Test
    @DisplayName("getAcceptedIssuers does not throw when issuer is a non-absolute tenant id")
    void getAcceptedIssuersDoesNotThrowForNonAbsoluteIssuer() {
        final var config = new ProviderConfig();
        config.setIssuer("common");
        config.setAliases(List.of("login.microsoftonline.com"));

        assertThatCode(() -> utils.getAcceptedIssuers(config)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("getAcceptedIssuers returns the issuer directly when it is an absolute URL")
    void getAcceptedIssuersReturnsAbsoluteIssuerDirectly() {
        final var config = new ProviderConfig();
        config.setIssuer("https://issuer.example.com/tenant/");
        config.setAliases(List.of("login.microsoftonline.com"));

        assertThat(utils.getAcceptedIssuers(config)).containsExactly("https://issuer.example.com/tenant/");
    }
}
