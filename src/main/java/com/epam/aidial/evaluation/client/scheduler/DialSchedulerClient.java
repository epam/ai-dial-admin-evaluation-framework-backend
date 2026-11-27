package com.epam.aidial.evaluation.client.scheduler;

import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerPage;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunListItem;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunResponse;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleRequest;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleResponse;
import com.epam.aidial.evaluation.configuration.properties.scheduler.SchedulerProperties;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * HTTP client for the schedules API of the DIAL Core scheduler application, reached through DIAL Core at
 * {@code /v1/deployments/applications/{appId}/route/v1/schedules/...}. The eval caller's credential is attached by
 * the shared caller-credential interceptor of the underlying {@link RestClient}.
 *
 * <p>Unlike {@code DialCoreClient} nothing here is retried: schedule writes are not idempotent, and a retried
 * create would register a second schedule. Every failure surfaces as a {@link DialCoreClientException}.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dial.scheduler.enabled", havingValue = "true")
public class DialSchedulerClient {

    private static final String SCHEDULES_SEGMENT = "schedules";
    private static final int BAD_GATEWAY = 502;
    private static final int FIRST_SERVER_ERROR = 500;

    private static final ParameterizedTypeReference<SchedulerPage<SchedulerRunListItem>> RUN_PAGE_TYPE =
            new ParameterizedTypeReference<>() {};

    @Qualifier("dialSchedulerRestClient")
    private final RestClient restClient;

    private final SchedulerProperties properties;

    public SchedulerScheduleResponse create(SchedulerScheduleRequest request) {
        return call(
                "create schedule",
                () -> restClient
                        .post()
                        .uri(builder -> collection(builder).build())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(SchedulerScheduleResponse.class));
    }

    public SchedulerScheduleResponse get(String scheduleId) {
        return call(
                "get schedule " + scheduleId,
                () -> restClient
                        .get()
                        .uri(builder -> item(builder).build(scheduleId))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(SchedulerScheduleResponse.class));
    }

    public SchedulerScheduleResponse update(String scheduleId, SchedulerScheduleRequest request) {
        return call(
                "update schedule " + scheduleId,
                () -> restClient
                        .put()
                        .uri(builder -> item(builder).build(scheduleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(SchedulerScheduleResponse.class));
    }

    public SchedulerScheduleResponse pause(String scheduleId) {
        return postAction(scheduleId, "pause");
    }

    public SchedulerScheduleResponse resume(String scheduleId) {
        return postAction(scheduleId, "resume");
    }

    public void delete(String scheduleId) {
        call(
                "delete schedule " + scheduleId,
                () -> restClient
                        .delete()
                        .uri(builder -> item(builder).build(scheduleId))
                        .retrieve()
                        .toBodilessEntity());
    }

    public SchedulerPage<SchedulerRunListItem> listRuns(String scheduleId, int limit, int offset) {
        return call(
                "list runs of schedule " + scheduleId,
                () -> restClient
                        .get()
                        .uri(builder -> item(builder)
                                .pathSegment("runs")
                                .queryParam("limit", limit)
                                .queryParam("offset", offset)
                                .build(scheduleId))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(RUN_PAGE_TYPE));
    }

    /** Detail of one fire, including the target's recorded response ({@code result}). */
    public SchedulerRunResponse getRun(String scheduleId, String runId) {
        return call(
                "get run " + runId + " of schedule " + scheduleId,
                () -> restClient
                        .get()
                        .uri(builder ->
                                item(builder).pathSegment("runs", "{runId}").build(scheduleId, runId))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(SchedulerRunResponse.class));
    }

    private SchedulerScheduleResponse postAction(String scheduleId, String action) {
        return call(
                action + " schedule " + scheduleId,
                () -> restClient
                        .post()
                        .uri(builder -> item(builder).pathSegment(action).build(scheduleId))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(SchedulerScheduleResponse.class));
    }

    /** {@code .../route/v1/schedules/} (the collection endpoint keeps its trailing slash). */
    private UriBuilder collection(UriBuilder builder) {
        // pathSegment("") would drop the trailing slash, so it is appended as a plain path.
        return schedulesRoot(builder).path("/");
    }

    /** {@code .../route/v1/schedules/{id}}; expand with the schedule id so that it is encoded as one segment. */
    private UriBuilder item(UriBuilder builder) {
        return schedulesRoot(builder).pathSegment("{scheduleId}");
    }

    private UriBuilder schedulesRoot(UriBuilder builder) {
        // The app id (e.g. "public/dial_scheduler__1.0.0") is a path made of several segments, not one value.
        return builder.pathSegment("v1", "deployments", "applications")
                .pathSegment(properties.getAppId().split("/"))
                .pathSegment("route", "v1", SCHEDULES_SEGMENT);
    }

    private <T> T call(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            logFailure(operation, e.getStatusCode().value(), e);
            throw new DialCoreClientException(e.getStatusCode(), e.getMessage(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            logFailure(operation, BAD_GATEWAY, e);
            throw new DialCoreClientException(
                    HttpStatusCode.valueOf(BAD_GATEWAY), "DIAL scheduler is unreachable: " + e.getMessage(), e);
        }
    }

    private static void logFailure(String operation, int status, Exception e) {
        if (status >= FIRST_SERVER_ERROR) {
            log.warn("DIAL scheduler call failed: {} -> {}: {}", operation, status, e.getMessage(), e);
        } else {
            log.debug("DIAL scheduler call rejected: {} -> {}: {}", operation, status, e.getMessage(), e);
        }
    }
}
