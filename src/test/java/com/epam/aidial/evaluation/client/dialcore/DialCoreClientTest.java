package com.epam.aidial.evaluation.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationListResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreDeploymentDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelListResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreToolsetDto;
import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
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
                {"data":[{"object":"model","id":"m1","display_name":"Model 1","display_version":"v1","owner":"org",\
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
                {"data":[{"object":"application","id":"a1","display_name":"App 1","owner":"org",\
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
                {"object":"model","id":"gpt-5","display_name":"GPT-5","display_version":"2025","owner":"org",\
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
                {"object":"application","id":"EntityExtractor","display_name":"Entity Extractor","owner":"org",\
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
    @DisplayName("getDeploymentById resolves a model deployment via the unified endpoint")
    void getDeploymentByIdResolvesModel() {
        String json = """
                {"object":"model","id":"gpt-5","display_name":"GPT-5","display_version":"2025","owner":"org",\
                "created_at":1000,"updated_at":2000,"interfaces":["chat","mcp"]}
                """;
        RequestMatcher getDeployment =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/deployments/gpt-5");
        server.expect(getDeployment).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreDeploymentDto deployment = client.getDeploymentById("gpt-5");

        assertThat(deployment).isInstanceOf(DialCoreModelDto.class);
        assertThat(deployment.getId()).isEqualTo("gpt-5");
        assertThat(deployment.getInterfaces()).containsExactly(InterfaceType.CHAT, InterfaceType.MCP);
        server.verify();
    }

    @Test
    @DisplayName("getDeploymentById resolves an application deployment via the unified endpoint")
    void getDeploymentByIdResolvesApplication() {
        String json = """
                {"object":"application","id":"EntityExtractor","display_name":"Entity Extractor","owner":"org",\
                "created_at":1000,"updated_at":2000}
                """;
        RequestMatcher getDeployment =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/deployments/EntityExtractor");
        server.expect(getDeployment).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreDeploymentDto deployment = client.getDeploymentById("EntityExtractor");

        assertThat(deployment).isInstanceOf(DialCoreApplicationDto.class);
        assertThat(deployment.getId()).isEqualTo("EntityExtractor");
        server.verify();
    }

    @Test
    @DisplayName("getDeploymentById resolves a toolset deployment via the unified endpoint")
    void getDeploymentByIdResolvesToolset() {
        String json = """
                {"object":"toolset","id":"my-toolset","display_name":"My Toolset","owner":"org",\
                "created_at":1000,"updated_at":2000}
                """;
        RequestMatcher getDeployment =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/deployments/my-toolset");
        server.expect(getDeployment).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreDeploymentDto deployment = client.getDeploymentById("my-toolset");

        assertThat(deployment).isInstanceOf(DialCoreToolsetDto.class);
        assertThat(deployment.getId()).isEqualTo("my-toolset");
        server.verify();
    }

    @Test
    @DisplayName("getDeploymentById throws DialCoreClientException carrying the raw status on 404")
    void getDeploymentByIdThrowsOn404() {
        RequestMatcher getDeployment =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/deployments/missing");
        server.expect(getDeployment).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getDeploymentById("missing"))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(e -> assertThat(
                                ((DialCoreClientException) e).getStatusCode().value())
                        .isEqualTo(404));
        server.verify();
    }

    @Test
    @DisplayName("getDeploymentById throws DialCoreClientException carrying the raw status on 403")
    void getDeploymentByIdThrowsOn403() {
        RequestMatcher getDeployment =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/deployments/forbidden");
        server.expect(getDeployment).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getDeploymentById("forbidden"))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(e -> assertThat(
                                ((DialCoreClientException) e).getStatusCode().value())
                        .isEqualTo(403));
        server.verify();
    }

    @Test
    @DisplayName("getDeploymentById throws DialCoreClientException carrying the raw status on 400")
    void getDeploymentByIdThrowsOn400() {
        RequestMatcher getDeployment =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/deployments/bad-id");
        server.expect(getDeployment).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.getDeploymentById("bad-id"))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(e -> assertThat(
                                ((DialCoreClientException) e).getStatusCode().value())
                        .isEqualTo(400));
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
                "{\"data\":[{\"object\":\"model\",\"id\":\"m1\",\"display_name\":\"M1\",\"owner\":\"o\",\"created_at\":1,\"updated_at\":2}]}";
        server.expect(listModels).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DialCoreModelListResponseDto response = clientWithRetry.getModels();

        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getId()).isEqualTo("m1");
        server.verify();
    }

    @Test
    @DisplayName("getUserInfo parses body Core labels as application/octet-stream")
    void getUserInfoParsesOctetStreamBody() {
        String json = "{\"sub\":\"user-123\",\"userClaims\":{\"email\":[\"jane@example.com\"]}}";
        RequestMatcher userInfo =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/user/info");
        server.expect(userInfo).andRespond(withSuccess(json, MediaType.APPLICATION_OCTET_STREAM));

        JsonNode response = client.getUserInfo();

        assertThat(response).isNotNull();
        assertThat(response.get("sub").asString()).isEqualTo("user-123");
        assertThat(response.get("userClaims").get("email").get(0).asString()).isEqualTo("jane@example.com");
        server.verify();
    }

    @Test
    @DisplayName("getUserInfo returns null on empty body")
    void getUserInfoReturnsNullOnEmptyBody() {
        RequestMatcher userInfo =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/user/info");
        server.expect(userInfo).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(client.getUserInfo()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("getUserInfo throws DialCoreClientException on 401 from Core")
    void getUserInfoThrowsOnUnauthorized() {
        RequestMatcher userInfo =
                request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/user/info");
        server.expect(userInfo).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getUserInfo())
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(e -> assertThat(
                                ((DialCoreClientException) e).getStatusCode().value())
                        .isEqualTo(401));
        server.verify();
    }
}
