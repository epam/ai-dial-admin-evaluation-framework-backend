package com.epam.aidial.evaluation.cli.client.source;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("SourceClientConfiguration")
class SourceClientConfigurationTest {

    @Test
    @DisplayName("staticApiKeyInterceptor sets the Api-Key header")
    void staticApiKeyInterceptorSetsApiKeyHeader() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://source-ef")
                .requestInterceptor(SourceClientConfiguration.staticApiKeyInterceptor("source-api-key"));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo("http://source-ef/ping"))
                .andExpect(header("Api-Key", "source-api-key"))
                .andRespond(withSuccess());

        restClient.get().uri("/ping").retrieve().toBodilessEntity();

        server.verify();
    }
}
