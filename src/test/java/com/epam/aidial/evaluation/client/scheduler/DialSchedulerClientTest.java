package com.epam.aidial.evaluation.client.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerCronFields;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerCronTrigger;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerPage;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunListItem;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunResponse;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleProperties;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleRequest;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleResponse;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerTrigger;
import com.epam.aidial.evaluation.configuration.properties.scheduler.SchedulerProperties;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DialSchedulerClient")
class DialSchedulerClientTest {

    private static final String BASE_URL = "http://core.test";
    private static final String SCHEDULES_ROOT =
            BASE_URL + "/v1/deployments/applications/public/dial_scheduler__1.0.0/route/v1/schedules";
    private static final String SCHEDULE_JSON = """
            {"id":"sch-1","display_name":"nightly","service_id":"dial-native","next_run_time":"2026-11-26T01:00:00Z",\
            "trigger":{"cron":{"fields":{"hour":"1"}}},"timezone":"UTC","properties":{"url":"http://ef/api"},\
            "is_deleted":false,"created_by":"alice","created_at":"2026-11-25T10:00:00Z",\
            "updated_at":"2026-11-25T10:00:00Z","unknown_future_field":42}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer server;
    private DialSchedulerClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestInterceptor(DialCoreClientConfiguration.callerCredentialInterceptor());
        server = MockRestServiceServer.bindTo(builder).build();
        SchedulerProperties properties = new SchedulerProperties();
        properties.setAppId("public/dial_scheduler__1.0.0");
        client = new DialSchedulerClient(builder.build(), properties);
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("caller-jwt"));
    }

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("create posts the snake_case body to the collection URL with a trailing slash and parses the response")
    void createPostsBodyToCollectionUrl() {
        server.expect(request -> {
                    assertThat(request.getURI().toString()).isEqualTo(SCHEDULES_ROOT + "/");
                    JsonNode body = bodyOf(request);
                    assertThat(body.get("display_name").asString()).isEqualTo("nightly");
                    assertThat(body.get("service_id").asString()).isEqualTo("dial-native");
                    assertThat(body.get("timezone").asString()).isEqualTo("Europe/Berlin");
                    assertThat(body.at("/trigger/cron/fields/hour").asString()).isEqualTo("1");
                    assertThat(body.at("/trigger/cron/fields/minute").isMissingNode())
                            .as("unset cron fields are omitted")
                            .isTrue();
                    assertThat(body.at("/properties/target_type").asString()).isEqualTo("rest");
                    assertThat(body.at("/properties/method").asString()).isEqualTo("POST");
                    assertThat(body.at("/properties/payload/runConfig/numberOfRuns")
                                    .asInt())
                            .isEqualTo(2);
                    assertThat(body.at("/properties/timeout").asInt()).isEqualTo(120);
                })
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED).body(SCHEDULE_JSON).contentType(MediaType.APPLICATION_JSON));

        SchedulerScheduleResponse response = client.create(sampleRequest());

        assertThat(response.getId()).isEqualTo("sch-1");
        assertThat(response.getDisplayName()).isEqualTo("nightly");
        assertThat(response.getNextRunTime()).isEqualTo(Instant.parse("2026-11-26T01:00:00Z"));
        assertThat(response.getTrigger().at("/cron/fields/hour").asString()).isEqualTo("1");
        assertThat(response.getProperties().get("url").asString()).isEqualTo("http://ef/api");
        server.verify();
    }

    @Test
    @DisplayName("sends the caller's bearer credential on every call")
    void sendsBearerCredential() {
        server.expect(header("Authorization", "Bearer caller-jwt"))
                .andRespond(withSuccess(SCHEDULE_JSON, MediaType.APPLICATION_JSON));

        client.get("sch-1");

        server.verify();
    }

    @Test
    @DisplayName("sends an API-key caller's credential under the Api-Key header")
    void sendsApiKeyCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("prk-1"));
        server.expect(header("Api-Key", "prk-1")).andRespond(withSuccess(SCHEDULE_JSON, MediaType.APPLICATION_JSON));

        client.get("sch-1");

        server.verify();
    }

    @Test
    @DisplayName("get, update, pause and resume use the item URL and the right verb")
    void itemOperationsUseItemUrl() {
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(SCHEDULE_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(SCHEDULE_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1/pause"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(SCHEDULE_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1/resume"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(SCHEDULE_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.get("sch-1").getId()).isEqualTo("sch-1");
        assertThat(client.update("sch-1", sampleRequest()).getId()).isEqualTo("sch-1");
        assertThat(client.pause("sch-1").getId()).isEqualTo("sch-1");
        assertThat(client.resume("sch-1").getId()).isEqualTo("sch-1");
        server.verify();
    }

    @Test
    @DisplayName("delete accepts 204 without a body")
    void deleteAccepts204() {
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        assertThatCode(() -> client.delete("sch-1")).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    @DisplayName("listRuns passes limit and offset and parses the page")
    void listRunsParsesPage() {
        String page = """
                {"count":2,"limit":5,"offset":10,"next":null,"previous":null,"results":[\
                {"id":"r1","status":"success","start_time":"2026-11-25T10:00:00Z","end_time":"2026-11-25T10:00:03Z"},\
                {"id":"r2","status":"in_progress","start_time":"2026-11-25T11:00:00Z","end_time":null}]}
                """;
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1/runs?limit=5&offset=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(page, MediaType.APPLICATION_JSON));

        SchedulerPage<SchedulerRunListItem> runs = client.listRuns("sch-1", 5, 10);

        assertThat(runs.getCount()).isEqualTo(2);
        assertThat(runs.getResults())
                .extracting(SchedulerRunListItem::getStatus)
                .containsExactly("success", "in_progress");
        assertThat(runs.getResults().get(0).getEndTime()).isEqualTo(Instant.parse("2026-11-25T10:00:03Z"));
        assertThat(runs.getResults().get(1).getEndTime()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("getRun requests the fire detail and parses the recorded target response")
    void getRunParsesRecordedResult() {
        String detail = """
                {"id":"r1","status":"success","start_time":"2026-11-25T10:00:00Z","end_time":"2026-11-25T10:00:03Z",\
                "conversation_id":null,"schedule":{"display_name":"nightly","trigger":{},"properties":{}},\
                "result":{"body":{"id":"3f0c6d3e-6a43-4a4f-9f0e-0c2b0d6a0001","status":"PENDING"},"status_code":202}}
                """;
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1/runs/r1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));

        SchedulerRunResponse run = client.getRun("sch-1", "r1");

        assertThat(run.getId()).isEqualTo("r1");
        assertThat(run.getStatus()).isEqualTo("success");
        assertThat(run.getEndTime()).isEqualTo(Instant.parse("2026-11-25T10:00:03Z"));
        assertThat(run.getResult().path("status_code").asInt()).isEqualTo(202);
        assertThat(run.getResult().at("/body/id").asString()).isEqualTo("3f0c6d3e-6a43-4a4f-9f0e-0c2b0d6a0001");
        server.verify();
    }

    @Test
    @DisplayName("getRun accepts a fire whose result is null")
    void getRunAcceptsNullResult() {
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1/runs/r2"))
                .andRespond(withSuccess(
                        "{\"id\":\"r2\",\"status\":\"missed\",\"start_time\":\"2026-11-25T10:00:00Z\","
                                + "\"end_time\":null,\"result\":null}",
                        MediaType.APPLICATION_JSON));

        SchedulerRunResponse run = client.getRun("sch-1", "r2");

        assertThat(run.getStatus()).isEqualTo("missed");
        assertThat(run.getResult() == null || run.getResult().isNull()).isTrue();
    }

    @Test
    @DisplayName("getRun maps a 404 to DialCoreClientException")
    void getRunNotFoundIsMapped() {
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1/runs/gone"))
                .andRespond(withResourceNotFound().body("{\"detail\":\"Run not found\"}"));

        assertThatThrownBy(() -> client.getRun("sch-1", "gone"))
                .isInstanceOfSatisfying(DialCoreClientException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(404);
                    assertThat(e.getResponseBody()).contains("Run not found");
                });
    }

    @Test
    @DisplayName("a schedule id is encoded as a single path segment")
    void scheduleIdIsEncodedAsOneSegment() {
        server.expect(requestTo(SCHEDULES_ROOT + "/a%2Fb"))
                .andRespond(withSuccess(SCHEDULE_JSON, MediaType.APPLICATION_JSON));

        client.get("a/b");

        server.verify();
    }

    @Test
    @DisplayName("a 404 surfaces as DialCoreClientException with the upstream status and body")
    void notFoundIsMapped() {
        server.expect(requestTo(SCHEDULES_ROOT + "/missing"))
                .andRespond(withResourceNotFound().body("{\"detail\":\"Schedule not found\"}"));

        assertThatThrownBy(() -> client.get("missing")).isInstanceOfSatisfying(DialCoreClientException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(404);
            assertThat(e.getResponseBody()).contains("Schedule not found");
        });
    }

    @Test
    @DisplayName("a 503 on create is mapped and exactly one request reaches the scheduler")
    void serverErrorIsNotRetried() {
        server.expect(requestTo(SCHEDULES_ROOT + "/")).andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> client.create(sampleRequest()))
                .isInstanceOfSatisfying(
                        DialCoreClientException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        server.verify();
    }

    @Test
    @DisplayName("401 and 403 from the scheduler keep their status")
    void authFailuresKeepStatus() {
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-2")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.get("sch-1"))
                .isInstanceOfSatisfying(
                        DialCoreClientException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> client.get("sch-2"))
                .isInstanceOfSatisfying(
                        DialCoreClientException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    @DisplayName("a connection failure is reported as 502")
    void connectionFailureIsBadGateway() {
        server.expect(requestTo(SCHEDULES_ROOT + "/sch-1")).andRespond(withException(new IOException("refused")));

        assertThatThrownBy(() -> client.get("sch-1")).isInstanceOfSatisfying(DialCoreClientException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(502);
            assertThat(e.getMessage()).contains("unreachable");
        });
    }

    private JsonNode bodyOf(ClientHttpRequest request) {
        return objectMapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
    }

    private static SchedulerScheduleRequest sampleRequest() {
        return SchedulerScheduleRequest.builder()
                .displayName("nightly")
                .serviceId("dial-native")
                .timezone("Europe/Berlin")
                .trigger(SchedulerTrigger.builder()
                        .cron(SchedulerCronTrigger.builder()
                                .fields(SchedulerCronFields.builder().hour("1").build())
                                .build())
                        .build())
                .properties(SchedulerScheduleProperties.builder()
                        .targetType("rest")
                        .method("POST")
                        .url("http://ef/api/v1/test-suites/s/runs")
                        .payload(Map.of("runConfig", Map.of("numberOfRuns", 2)))
                        .timeout(120)
                        .build())
                .build();
    }
}
