package com.epam.aidial.evaluation.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationListResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelListResponseDto;
import com.epam.aidial.evaluation.configuration.properties.dial.DialCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DialCoreClient")
class DialCoreClientTest {

    private MockRestServiceServer server;
    private RestClient restClient;
    private DialCoreClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        DialCoreProperties properties = new DialCoreProperties();
        properties.setRetry(new DialCoreProperties.Retry());
        properties.getRetry().setMaxAttempts(1);
        client = new DialCoreClient(restClient, properties, new ObjectMapper());
    }

    @Test
    @DisplayName("getModels returns parsed list")
    void getModelsReturnsParsedList() {
        String json = """
                {"data":[{"id":"m1","display_name":"Model 1","display_version":"v1","owner":"org",\
                "created_at":1000,"updated_at":2000}]}
                """;
        RequestMatcher listModels =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/openai/models");
        server.expect(listModels).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreModelListResponseDto response = client.getModels();

        assertThat(response).isNotNull();
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getId()).isEqualTo("m1");
        assertThat(response.getData().get(0).getDisplayName()).isEqualTo("Model 1");
        server.verify();
    }

    @Test
    @DisplayName("getApplications returns parsed list")
    void getApplicationsReturnsParsedList() {
        String json = """
                {"data":[{"id":"a1","display_name":"App 1","owner":"org",\
                "created_at":1000,"updated_at":2000}]}
                """;
        RequestMatcher listApps =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/openai/applications");
        server.expect(listApps).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreApplicationListResponseDto response = client.getApplications();

        assertThat(response).isNotNull();
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getId()).isEqualTo("a1");
        server.verify();
    }

    @Test
    @DisplayName("getModel returns single model")
    void getModelReturnsSingleModel() {
        String json = """
                {"id":"gpt-5","display_name":"GPT-5","display_version":"2025","owner":"org",\
                "created_at":1000,"updated_at":2000}
                """;
        RequestMatcher getModel =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/openai/models/gpt-5");
        server.expect(getModel).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreModelDto model = client.getModel("gpt-5");

        assertThat(model).isNotNull();
        assertThat(model.getId()).isEqualTo("gpt-5");
        assertThat(model.getDisplayName()).isEqualTo("GPT-5");
        server.verify();
    }

    @Test
    @DisplayName("getApplication returns single application")
    void getApplicationReturnsSingleApplication() {
        String json = """
                {"id":"EntityExtractor","display_name":"Entity Extractor","owner":"org",\
                "created_at":1000,"updated_at":2000}
                """;
        RequestMatcher getApp =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/openai/applications/EntityExtractor");
        server.expect(getApp).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreApplicationDto app = client.getApplication("EntityExtractor");

        assertThat(app).isNotNull();
        assertThat(app.getId()).isEqualTo("EntityExtractor");
        server.verify();
    }

    @Test
    @DisplayName("throws DialCoreClientException on 404")
    void throwsDialCoreClientExceptionOn404() {
        RequestMatcher listModels =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/openai/models");
        server.expect(listModels).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getModels()).isInstanceOf(DialCoreClientException.class);
        server.verify();
    }

    @Test
    @DisplayName("throws DialCoreClientException on 5xx")
    void throwsDialCoreClientExceptionOn5xx() {
        RequestMatcher listModels =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/openai/models");
        server.expect(listModels).andRespond(withServerError());

        assertThatThrownBy(() -> client.getModels()).isInstanceOf(DialCoreClientException.class);
        server.verify();
    }

    @Test
    @DisplayName("retries on transient 503 then succeeds")
    void retriesOnTransientFailureThenSucceeds() {
        DialCoreProperties retryProps = new DialCoreProperties();
        DialCoreProperties.Retry retry = new DialCoreProperties.Retry();
        retry.setMaxAttempts(3);
        retry.setDelayMs(10);
        retry.setMultiplier(1.0);
        retryProps.setRetry(retry);
        DialCoreClient clientWithRetry = new DialCoreClient(restClient, retryProps, new ObjectMapper());

        RequestMatcher listModels =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/openai/models");
        server.expect(listModels).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(listModels).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        String json =
                "{\"data\":[{\"id\":\"m1\",\"display_name\":\"M1\",\"owner\":\"o\",\"created_at\":1,\"updated_at\":2}]}";
        server.expect(listModels).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreModelListResponseDto response = clientWithRetry.getModels();

        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getId()).isEqualTo("m1");
        server.verify();
    }
}
