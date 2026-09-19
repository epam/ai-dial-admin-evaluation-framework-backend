package com.epam.aidial.evaluation.cli.client.target;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Asserts {@code TargetDialCoreClientConfiguration#apiKeyInterceptor()} emits {@code Api-Key} only
 * for an {@code API_KEY}-kind credential, mirroring
 * {@link com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfigurationTest}'s
 * {@code RestClient#mutate()} + {@link MockRestServiceServer} approach.
 */
@DisplayName("TargetDialCoreClientConfiguration")
class TargetDialCoreClientConfigurationTest {

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("sends Api-Key header for an API-key credential")
    void sendsApiKeyHeaderForApiKeyCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("key-1"));

        RestClient.Builder mutated = buildRestClient().mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(mutated).build();
        RestClient testClient = mutated.build();

        server.expect(requestTo("http://dial-core.local/ping"))
                .andExpect(header(CallerCredential.API_KEY_HEADER, "key-1"))
                .andRespond(withSuccess());

        testClient.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("sends no Api-Key header for a bearer credential")
    void sendsNoHeaderForBearerCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("jwt-1"));

        RestClient.Builder mutated = buildRestClient().mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(mutated).build();
        RestClient testClient = mutated.build();

        server.expect(requestTo("http://dial-core.local/ping"))
                .andExpect(headerDoesNotExist(CallerCredential.API_KEY_HEADER))
                .andRespond(withSuccess());

        testClient.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("sends no Api-Key header when no credential is captured")
    void sendsNoHeaderForAbsentCredential() {
        RestClient.Builder mutated = buildRestClient().mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(mutated).build();
        RestClient testClient = mutated.build();

        server.expect(requestTo("http://dial-core.local/ping"))
                .andExpect(headerDoesNotExist(CallerCredential.API_KEY_HEADER))
                .andRespond(withSuccess());

        testClient.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }

    private static RestClient buildRestClient() {
        DialCoreProperties properties = new DialCoreProperties();
        properties.setBaseUrl("http://dial-core.local");
        properties.setConnectTimeoutMs(1000);
        DialCoreProperties.TryOut tryOut = new DialCoreProperties.TryOut();
        tryOut.setReadTimeoutMs(1000);
        properties.setTryOut(tryOut);

        return new TargetDialCoreClientConfiguration().dialCoreTryOutRestClient(RestClient.builder(), properties);
    }
}
