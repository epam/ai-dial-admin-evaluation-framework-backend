package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.scheduler.DialSchedulerClient;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunListItem;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunResponse;
import com.epam.aidial.evaluation.constants.ScheduleConstants;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ScheduleFireRunResolver")
@ExtendWith(MockitoExtension.class)
class ScheduleFireRunResolverTest {

    private static final String SCHEDULE_ID = "sch-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduleFireRunExtractor extractor = new ScheduleFireRunExtractor();
    private final List<ScheduleFireRunResolver> resolvers = new ArrayList<>();

    @Mock
    private DialSchedulerClient schedulerClient;

    @AfterEach
    void tearDown() {
        resolvers.forEach(ScheduleFireRunResolver::shutdown);
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("shouldFetchDetailOnlyForSuccessFires")
    void shouldFetchDetailOnlyForSuccessFires() {
        final UUID runId = UUID.randomUUID();
        when(schedulerClient.getRun(SCHEDULE_ID, "ok")).thenReturn(detail("ok", runId.toString(), 202));

        final Map<String, UUID> resolved = resolver()
                .resolveRunIds(
                        SCHEDULE_ID,
                        List.of(
                                fire("ok", "success"),
                                fire("err", "error"),
                                fire("run", "in_progress"),
                                fire("m", "missed")));

        assertThat(resolved).containsOnly(Map.entry("ok", runId));
        verify(schedulerClient, times(1)).getRun(eq(SCHEDULE_ID), any());
    }

    @Test
    @DisplayName("shouldCacheFinishedFiresIncludingThoseWithoutARun")
    void shouldCacheFinishedFires() {
        final UUID runId = UUID.randomUUID();
        when(schedulerClient.getRun(SCHEDULE_ID, "ok")).thenReturn(detail("ok", runId.toString(), 202));
        when(schedulerClient.getRun(SCHEDULE_ID, "throttled")).thenReturn(detail("throttled", runId.toString(), 429));
        final ScheduleFireRunResolver resolver = resolver();
        final List<SchedulerRunListItem> fires = List.of(fire("ok", "success"), fire("throttled", "success"));

        final Map<String, UUID> first = resolver.resolveRunIds(SCHEDULE_ID, fires);
        final Map<String, UUID> second = resolver.resolveRunIds(SCHEDULE_ID, fires);

        assertThat(first).containsOnly(Map.entry("ok", runId));
        assertThat(second).isEqualTo(first);
        verify(schedulerClient, times(1)).getRun(SCHEDULE_ID, "ok");
        verify(schedulerClient, times(1)).getRun(SCHEDULE_ID, "throttled");
    }

    @Test
    @DisplayName("shouldIsolateAFailedDetailCallAndRetryItOnTheNextRequest")
    void shouldIsolateFailureAndNotCacheIt() {
        final UUID runId = UUID.randomUUID();
        when(schedulerClient.getRun(SCHEDULE_ID, "bad"))
                .thenThrow(new DialCoreClientException(HttpStatus.BAD_GATEWAY, "boom"))
                .thenReturn(detail("bad", runId.toString(), 202));
        when(schedulerClient.getRun(SCHEDULE_ID, "good")).thenReturn(detail("good", runId.toString(), 202));
        final ScheduleFireRunResolver resolver = resolver();
        final List<SchedulerRunListItem> fires = List.of(fire("bad", "success"), fire("good", "success"));

        final Map<String, UUID> first = resolver.resolveRunIds(SCHEDULE_ID, fires);
        final Map<String, UUID> second = resolver.resolveRunIds(SCHEDULE_ID, fires);

        assertThat(first).containsOnlyKeys("good");
        assertThat(second).containsOnlyKeys("good", "bad");
        verify(schedulerClient, times(2)).getRun(SCHEDULE_ID, "bad");
        verify(schedulerClient, times(1)).getRun(SCHEDULE_ID, "good");
    }

    @Test
    @DisplayName("shouldNotCallTheSchedulerWhenThereAreNoSuccessFires")
    void shouldNotCallSchedulerWithoutSuccessFires() {
        final Map<String, UUID> resolved =
                resolver().resolveRunIds(SCHEDULE_ID, List.of(fire("run", "in_progress"), fire("err", "error")));

        assertThat(resolved).isEmpty();
        verify(schedulerClient, never()).getRun(any(), any());
    }

    @Test
    @DisplayName("shouldCarryTheCallersCredentialOntoTheWorkerThreads")
    void shouldPropagateCredential() {
        final CallerCredential credential = CallerCredential.bearer("caller-jwt");
        final List<CallerCredential> seen = new ArrayList<>();
        when(schedulerClient.getRun(eq(SCHEDULE_ID), any())).thenAnswer(invocation -> {
            synchronized (seen) {
                seen.add(AuthorizationTokenHolder.getCredential());
            }
            return detail(invocation.getArgument(1), UUID.randomUUID().toString(), 202);
        });
        AuthorizationTokenHolder.setCredential(credential);

        resolver().resolveRunIds(SCHEDULE_ID, List.of(fire("a", "success"), fire("b", "success")));

        assertThat(seen).containsExactly(credential, credential);
    }

    @Test
    @DisplayName("shouldNeverRunMoreDetailCallsAtOnceThanTheConcurrencyBound")
    void shouldBoundConcurrency() {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        when(schedulerClient.getRun(eq(SCHEDULE_ID), any())).thenAnswer(invocation -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } finally {
                inFlight.decrementAndGet();
            }
            return detail(invocation.getArgument(1), UUID.randomUUID().toString(), 202);
        });
        final List<SchedulerRunListItem> fires = new ArrayList<>();
        for (int i = 0; i < ScheduleConstants.FIRE_DETAIL_CONCURRENCY * 4; i++) {
            fires.add(fire("fire-" + i, "success"));
        }

        final Map<String, UUID> resolved = resolver().resolveRunIds(SCHEDULE_ID, fires);

        assertThat(resolved).hasSize(fires.size());
        assertThat(peak.get()).isPositive().isLessThanOrEqualTo(ScheduleConstants.FIRE_DETAIL_CONCURRENCY);
    }

    private ScheduleFireRunResolver resolver() {
        final ScheduleFireRunResolver resolver = new ScheduleFireRunResolver(schedulerClient, extractor);
        resolvers.add(resolver);
        return resolver;
    }

    private static SchedulerRunListItem fire(String id, String status) {
        return SchedulerRunListItem.builder().id(id).status(status).build();
    }

    private SchedulerRunResponse detail(String fireId, String runId, int statusCode) {
        final JsonNode result =
                objectMapper.readTree("{\"body\":{\"id\":\"%s\"},\"status_code\":%d}".formatted(runId, statusCode));
        return SchedulerRunResponse.builder()
                .id(fireId)
                .status("success")
                .result(result)
                .build();
    }
}
