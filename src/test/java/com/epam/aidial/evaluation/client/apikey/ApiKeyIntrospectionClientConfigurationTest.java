package com.epam.aidial.evaluation.client.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ApiKeyIntrospectionClientConfiguration")
class ApiKeyIntrospectionClientConfigurationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    @DisplayName("parses a JSON body served with content type application/octet-stream")
    void parsesJsonBodyServedAsOctetStream() {
        ApiKeyProperties properties = new ApiKeyProperties(new ObjectMapper());
        properties.setCoreUrl("http://core");
        properties.setRequestTimeoutMs(1000);

        RestClient restClient = new ApiKeyIntrospectionClientConfiguration()
                .apiKeyIntrospectionRestClient(properties, OpenTelemetry.noop());

        RestClient.Builder mutated = restClient.mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(mutated).build();
        RestClient testClient = mutated.build();

        server.expect(requestTo("http://core/v1/user/info"))
                .andRespond(withSuccess("{\"project\":\"my-project\"}", MediaType.APPLICATION_OCTET_STREAM));

        Map<String, Object> body =
                testClient.get().uri("/v1/user/info").retrieve().body(STRING_OBJECT_MAP);

        assertThat(body).containsEntry("project", "my-project");
    }
}
