package com.epam.aidial.evaluation.client.dialcore;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClientConfiguration;
import com.epam.aidial.evaluation.configuration.properties.dialadas.DialAdasProperties;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Asserts {@link DialCoreClientConfiguration#callerCredentialInterceptor()} selects exactly one
 * outbound header per {@link com.epam.aidial.evaluation.runner.util.CredentialKind}, and that the
 * three production {@link RestClient} beans that reuse it actually register it.
 *
 * <p>The production-beans case instantiates each {@code @Configuration} class directly (no Spring
 * context) and sends a request through the built bean's {@link RestClient#mutate()} builder bound to
 * a {@link MockRestServiceServer}, exactly as {@code ApiKeyIntrospectionClientConfigurationTest} does
 * for a single client — this is cheaper than booting a context and still proves the interceptor is on
 * the bean, not just on the shared factory method.
 */
@DisplayName("DialCoreClientConfiguration")
class DialCoreClientConfigurationTest {

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("sends Authorization: Bearer and no Api-Key for a bearer credential")
    void sendsBearerHeaderForBearerCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("jwt-1"));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://dial-core.local")
                .requestInterceptor(DialCoreClientConfiguration.callerCredentialInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        server.expect(requestTo("http://dial-core.local/ping"))
                .andExpect(header("Authorization", "Bearer jwt-1"))
                .andExpect(headerDoesNotExist(CallerCredential.API_KEY_HEADER))
                .andRespond(withSuccess());

        client.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("sends Api-Key and no Authorization for an api-key credential")
    void sendsApiKeyHeaderForApiKeyCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("key-1"));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://dial-core.local")
                .requestInterceptor(DialCoreClientConfiguration.callerCredentialInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        server.expect(requestTo("http://dial-core.local/ping"))
                .andExpect(header(CallerCredential.API_KEY_HEADER, "key-1"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess());

        client.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("sends neither header when no credential is captured")
    void sendsNoHeaderForAbsentCredential() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://dial-core.local")
                .requestInterceptor(DialCoreClientConfiguration.callerCredentialInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        server.expect(requestTo("http://dial-core.local/ping"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(headerDoesNotExist(CallerCredential.API_KEY_HEADER))
                .andRespond(withSuccess());

        client.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("dialCoreRestClient registers the caller-credential interceptor")
    void dialCoreRestClientRegistersInterceptor() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("key-core"));

        DialCoreProperties properties = new DialCoreProperties();
        properties.setBaseUrl("http://dial-core.local");
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(1000);

        RestClient restClient = new DialCoreClientConfiguration().dialCoreRestClient(properties, OpenTelemetry.noop());
        assertInterceptorSendsApiKey(restClient, "http://dial-core.local", "key-core");
    }

    @Test
    @DisplayName("dialCoreTryOutRestClient registers the caller-credential interceptor")
    void dialCoreTryOutRestClientRegistersInterceptor() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("key-tryout"));

        DialCoreProperties properties = new DialCoreProperties();
        properties.setBaseUrl("http://dial-core.local");
        properties.setConnectTimeoutMs(1000);
        DialCoreProperties.TryOut tryOut = new DialCoreProperties.TryOut();
        tryOut.setReadTimeoutMs(1000);
        properties.setTryOut(tryOut);

        RestClient restClient =
                new DialCoreDeploymentInvokerConfiguration().dialCoreTryOutRestClient(properties, OpenTelemetry.noop());
        assertInterceptorSendsApiKey(restClient, "http://dial-core.local", "key-tryout");
    }

    @Test
    @DisplayName("dialAdasRestClient registers the caller-credential interceptor")
    void dialAdasRestClientRegistersInterceptor() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("key-adas"));

        DialAdasProperties properties = new DialAdasProperties();
        properties.setBaseUrl("http://dial-adas.local");
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(1000);

        RestClient restClient = new DialAdasClientConfiguration().dialAdasRestClient(properties, OpenTelemetry.noop());
        assertInterceptorSendsApiKey(restClient, "http://dial-adas.local", "key-adas");
    }

    private static void assertInterceptorSendsApiKey(RestClient restClient, String baseUrl, String expectedKey) {
        RestClient.Builder mutated = restClient.mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(mutated).build();
        RestClient testClient = mutated.build();

        server.expect(requestTo(baseUrl + "/ping"))
                .andExpect(header(CallerCredential.API_KEY_HEADER, expectedKey))
                .andRespond(withSuccess());

        testClient.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }
}
