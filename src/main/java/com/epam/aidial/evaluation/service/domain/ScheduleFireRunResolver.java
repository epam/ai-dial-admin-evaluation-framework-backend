package com.epam.aidial.evaluation.service.domain;

import static com.epam.aidial.evaluation.constants.ScheduleConstants.FIRE_DETAIL_CONCURRENCY;
import static com.epam.aidial.evaluation.constants.ScheduleConstants.FIRE_RUN_CACHE_MAX_SIZE;
import static com.epam.aidial.evaluation.constants.ScheduleConstants.FIRE_STATUS_SUCCESS;

import com.epam.aidial.evaluation.client.scheduler.DialSchedulerClient;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunListItem;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunResponse;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.runner.util.TokenPropagationHelper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Finds the EF run each fire of a schedule started. The scheduler's fire list has no recorded response, so every
 * fire that can have started a run needs one detail call; this class keeps that affordable and harmless:
 *
 * <ul>
 *   <li>only {@code success} fires are fetched (any other fire never reached EF);
 *   <li>detail calls run with bounded concurrency, carrying the caller's credential onto the worker threads;
 *   <li>a finished fire never changes, so its extracted run id (including "no run") is cached;
 *   <li>a failed detail call leaves that fire without a run id and is not cached, so the next request retries it. It
 *       never fails the page: the link is re-derivable.
 * </ul>
 */
@Slf4j
@Component
@LogExecution
@ConditionalOnProperty(name = "dial.scheduler.enabled", havingValue = "true")
public class ScheduleFireRunResolver {

    private final DialSchedulerClient schedulerClient;
    private final ScheduleFireRunExtractor extractor;
    private final Cache<String, Optional<UUID>> finishedFireRunIds =
            Caffeine.newBuilder().maximumSize(FIRE_RUN_CACHE_MAX_SIZE).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(
            FIRE_DETAIL_CONCURRENCY,
            Thread.ofVirtual().name("schedule-fire-detail-", 0).factory());

    public ScheduleFireRunResolver(DialSchedulerClient schedulerClient, ScheduleFireRunExtractor extractor) {
        this.schedulerClient = schedulerClient;
        this.extractor = extractor;
    }

    /** Maps fire id to the id of the EF run it started; fires without a known run are absent. */
    public Map<String, UUID> resolveRunIds(String scheduleId, List<SchedulerRunListItem> fires) {
        final CallerCredential credential = AuthorizationTokenHolder.getCredential();
        final Map<String, CompletableFuture<Optional<UUID>>> pending = new HashMap<>();
        final Map<String, UUID> resolved = new HashMap<>();
        for (final SchedulerRunListItem fire : fires) {
            if (!FIRE_STATUS_SUCCESS.equals(fire.getStatus())) {
                continue;
            }
            final Optional<UUID> cached = finishedFireRunIds.getIfPresent(cacheKey(scheduleId, fire.getId()));
            if (cached != null) {
                cached.ifPresent(runId -> resolved.put(fire.getId(), runId));
            } else {
                pending.put(fire.getId(), fetchAsync(scheduleId, fire.getId(), credential));
            }
        }
        pending.forEach((fireId, future) -> future.join().ifPresent(runId -> resolved.put(fireId, runId)));
        return resolved;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private CompletableFuture<Optional<UUID>> fetchAsync(
            String scheduleId, String fireId, CallerCredential credential) {
        return CompletableFuture.supplyAsync(
                        TokenPropagationHelper.withCredential(credential, () -> fetch(scheduleId, fireId)), executor)
                .handle((runId, error) -> {
                    if (error == null) {
                        return runId;
                    }
                    log.warn(
                            "Could not read the recorded response of fire {} of schedule {}: {}",
                            fireId,
                            scheduleId,
                            error.getMessage(),
                            error);
                    return Optional.empty();
                });
    }

    private Optional<UUID> fetch(String scheduleId, String fireId) {
        final SchedulerRunResponse detail = schedulerClient.getRun(scheduleId, fireId);
        final Optional<UUID> runId = extractor.extractRunId(detail.getResult());
        finishedFireRunIds.put(cacheKey(scheduleId, fireId), runId);
        return runId;
    }

    private static String cacheKey(String scheduleId, String fireId) {
        return scheduleId + ":" + fireId;
    }
}
