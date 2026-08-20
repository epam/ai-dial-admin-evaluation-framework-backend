package com.epam.aidial.evaluation.client.dialadas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateQueryDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("DialAdasClient")
class DialAdasClientTest {

    private MockRestServiceServer server;
    private RestClient.Builder builder;
    private DialAdasClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://dial-adas.local");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DialAdasClient(builder.build());
    }

    @Test
    @DisplayName("executeAggregate posts the query body and parses the aggregate response")
    void executeAggregatePostsQueryAndParsesResponse() {
        ObjectNode filter = new ObjectMapper().createObjectNode();
        filter.put("op", "and");
        AdasAggregateQueryDto query = AdasAggregateQueryDto.builder()
                .entity("dial_usage_log")
                .mode("aggregate")
                .filter(filter)
                .groupBy(List.of())
                .select(List.of())
                .build();

        server.expect(requestTo("http://dial-adas.local/v1/queries/execute"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"entity\":\"dial_usage_log\",\"mode\":\"aggregate\"}"))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"count\":29159,\"avg_cost\":0.004220310571493558}]}",
                        MediaType.APPLICATION_JSON));

        AdasAggregateResponseDto response = client.executeAggregate(query);

        assertThat(response).isNotNull();
        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0).getCount()).isEqualTo(29159L);
        assertThat(response.getRows().get(0).getAvgCost()).isEqualTo(0.004220310571493558);
        server.verify();
    }

    @Test
    @DisplayName("throws DialAdasClientException with 502 on 5xx response")
    void throwsClientExceptionOn5xx() {
        AdasAggregateQueryDto query = AdasAggregateQueryDto.builder()
                .entity("dial_usage_log")
                .mode("aggregate")
                .build();
        server.expect(requestTo("http://dial-adas.local/v1/queries/execute")).andRespond(withServerError());

        assertThatThrownBy(() -> client.executeAggregate(query))
                .isInstanceOf(DialAdasClientException.class)
                .extracting(ex -> ((DialAdasClientException) ex).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        server.verify();
    }

    @Test
    @DisplayName("throws DialAdasClientException with 504 on connection timeout")
    void throwsClientExceptionOnTimeout() {
        ClientHttpRequestInterceptor timeoutInterceptor = (request, body, execution) -> throwSocketTimeout();
        RestClient timeoutClient =
                builder.requestInterceptor(timeoutInterceptor).build();
        DialAdasClient timeoutBoundClient = new DialAdasClient(timeoutClient);
        AdasAggregateQueryDto query = AdasAggregateQueryDto.builder()
                .entity("dial_usage_log")
                .mode("aggregate")
                .build();

        assertThatThrownBy(() -> timeoutBoundClient.executeAggregate(query))
                .isInstanceOf(DialAdasClientException.class)
                .extracting(ex -> ((DialAdasClientException) ex).getStatusCode())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
    }

    private static ClientHttpResponse throwSocketTimeout() throws IOException {
        throw new SocketTimeoutException("Read timed out");
    }
}
