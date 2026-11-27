package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.scheduler.DialSchedulerClient;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerPage;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunListItem;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleRequest;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleResponse;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteScheduleRepository;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleRunDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteScheduleMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Manages cron schedules for test suite runs. The schedule itself lives in the DIAL Core scheduler application;
 * EF keeps only a mapping row (schedule id to suite id) so a suite's schedules can be listed and so that only
 * schedules created through EF can be managed through it.
 *
 * <p>Deliberately not transactional: every operation makes HTTP calls to the scheduler, and a database transaction
 * must not stay open across them. The mapping writes are single statements.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dial.scheduler.enabled", havingValue = "true")
public class TestSuiteScheduleService {

    private final DialSchedulerClient schedulerClient;
    private final TestSuiteScheduleRepository repository;
    private final TestSuiteService testSuiteService;
    private final RunConfigValidator runConfigValidator;
    private final ScheduleRequestBuilder requestBuilder;
    private final TestSuiteScheduleMapper mapper;
    private final AuthorResolver authorResolver;
    private final ScheduleFireRunResolver fireRunResolver;
    private final TestSuiteRunService testSuiteRunService;
    private final Clock clock;

    /**
     * Creates the schedule in the scheduler, then records the suite link. If recording fails the schedule is
     * removed again (best effort) so it cannot fire without being manageable.
     */
    public TestSuiteScheduleResponseDto create(UUID suiteId, TestSuiteScheduleRequestDto request, Jwt jwt) {
        requireSuite(suiteId);
        runConfigValidator.validate(request.getRunConfig());
        final String scheduleRef = UUID.randomUUID().toString();
        final SchedulerScheduleRequest body = requestBuilder.build(suiteId, request, scheduleRef);

        final SchedulerScheduleResponse remote = schedulerClient.create(body);
        final TestSuiteSchedule link = TestSuiteSchedule.builder()
                .scheduleId(remote.getId())
                .testSuiteId(suiteId)
                .createdBy(authorResolver.getCreatedBy(jwt))
                .createdAt(clock.millis())
                .scheduleRef(scheduleRef)
                .build();
        saveLinkOrRemoveSchedule(link);
        return mapper.toDto(remote, link);
    }

    /**
     * Lists the suite's schedules, newest first. Each mapped schedule is fetched from the scheduler; one that no
     * longer exists there (deleted outside EF) is omitted, so a page can hold fewer items than its size.
     */
    public PageResponseDto<TestSuiteScheduleResponseDto> listForSuite(UUID suiteId, int page, int size) {
        requireSuite(suiteId);
        final Page<TestSuiteSchedule> links = repository.findPageByTestSuiteId(suiteId, PageRequest.of(page, size));
        final List<TestSuiteScheduleResponseDto> content = links.getContent().stream()
                .map(this::fetchActive)
                .flatMap(Optional::stream)
                .toList();
        return PageResponseDto.<TestSuiteScheduleResponseDto>builder()
                .content(content)
                .page(links.getPage())
                .size(links.getSize())
                .totalElements(links.getTotalElements())
                .totalPages(links.getTotalPages())
                .build();
    }

    public TestSuiteScheduleResponseDto get(String scheduleId) {
        final TestSuiteSchedule link = requireLink(scheduleId);
        return fetchActive(link).orElseThrow(() -> scheduleNotFound(scheduleId));
    }

    /** Replaces name, description, cron, timezone and run configuration; the suite of a schedule never changes. */
    public TestSuiteScheduleResponseDto update(String scheduleId, TestSuiteScheduleRequestDto request) {
        final TestSuiteSchedule link = requireLink(scheduleId);
        runConfigValidator.validate(request.getRunConfig());
        final String existingRef = link.getScheduleRef();
        final String scheduleRef =
                existingRef != null ? existingRef : UUID.randomUUID().toString();
        final SchedulerScheduleRequest body = requestBuilder.build(link.getTestSuiteId(), request, scheduleRef);
        final SchedulerScheduleResponse remote = schedulerClient.update(scheduleId, body);
        if (existingRef == null) {
            // Created before run tagging existed: the schedule now fires with a reference, so remember it.
            repository.updateRef(scheduleId, scheduleRef);
            link.setScheduleRef(scheduleRef);
        }
        return mapper.toDto(remote, link);
    }

    public TestSuiteScheduleResponseDto pause(String scheduleId) {
        final TestSuiteSchedule link = requireLink(scheduleId);
        return mapper.toDto(schedulerClient.pause(scheduleId), link);
    }

    public TestSuiteScheduleResponseDto resume(String scheduleId) {
        final TestSuiteSchedule link = requireLink(scheduleId);
        return mapper.toDto(schedulerClient.resume(scheduleId), link);
    }

    /** Deletes the schedule in the scheduler (already gone is fine), then its mapping row. Started runs are kept. */
    public void delete(String scheduleId) {
        requireLink(scheduleId);
        try {
            schedulerClient.delete(scheduleId);
        } catch (DialCoreClientException e) {
            if (!isNotFound(e)) {
                throw e;
            }
            log.info("Schedule {} was already absent from the scheduler: {}", scheduleId, e.getMessage(), e);
        }
        repository.deleteById(scheduleId);
    }

    /**
     * Pages the scheduler's fire history of a schedule. Each fire that started an EF run also carries that run's id
     * and its current status (read live from EF); a deleted run keeps its id and shows no status.
     */
    public PageResponseDto<ScheduleRunDto> listRuns(String scheduleId, int page, int size) {
        requireLink(scheduleId);
        final PageRequest pageRequest = PageRequest.of(page, size);
        final SchedulerPage<SchedulerRunListItem> remote =
                schedulerClient.listRuns(scheduleId, size, pageRequest.getOffset());
        final Map<String, UUID> runIdsByFire = fireRunResolver.resolveRunIds(scheduleId, remote.getResults());
        final Map<UUID, String> statusesByRun =
                runIdsByFire.isEmpty() ? Map.of() : testSuiteRunService.findStatusesByIds(runIdsByFire.values());
        final List<ScheduleRunDto> content = remote.getResults().stream()
                .map(fire -> toRunDto(fire, runIdsByFire, statusesByRun))
                .toList();
        return PageResponseDto.<ScheduleRunDto>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(remote.getCount())
                .totalPages((int) Math.ceil((double) remote.getCount() / size))
                .build();
    }

    private ScheduleRunDto toRunDto(
            SchedulerRunListItem fire, Map<String, UUID> runIdsByFire, Map<UUID, String> statusesByRun) {
        final ScheduleRunDto dto = mapper.toRunDto(fire);
        final UUID runId = runIdsByFire.get(fire.getId());
        if (runId != null) {
            dto.setTestSuiteRunId(runId);
            dto.setTestSuiteRunStatus(statusesByRun.get(runId));
        }
        return dto;
    }

    private void requireSuite(UUID suiteId) {
        if (!testSuiteService.existsById(suiteId)) {
            throw new EntityNotFoundException("TestSuite not found with id: " + suiteId);
        }
    }

    private TestSuiteSchedule requireLink(String scheduleId) {
        return repository.findById(scheduleId).orElseThrow(() -> scheduleNotFound(scheduleId));
    }

    private static EntityNotFoundException scheduleNotFound(String scheduleId) {
        return new EntityNotFoundException("Schedule not found with id: " + scheduleId);
    }

    /** Empty when the scheduler reports the schedule as missing or soft-deleted. */
    private Optional<TestSuiteScheduleResponseDto> fetchActive(TestSuiteSchedule link) {
        final SchedulerScheduleResponse remote;
        try {
            remote = schedulerClient.get(link.getScheduleId());
        } catch (DialCoreClientException e) {
            if (!isNotFound(e)) {
                throw e;
            }
            log.warn(
                    "Schedule {} of suite {} no longer exists in the scheduler: {}",
                    link.getScheduleId(),
                    link.getTestSuiteId(),
                    e.getMessage(),
                    e);
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(remote.getIsDeleted())) {
            return Optional.empty();
        }
        return Optional.of(mapper.toDto(remote, link));
    }

    private void saveLinkOrRemoveSchedule(TestSuiteSchedule link) {
        try {
            repository.save(link);
        } catch (DataAccessException e) {
            log.error(
                    "Failed to record schedule {} for suite {}; removing it from the scheduler",
                    link.getScheduleId(),
                    link.getTestSuiteId(),
                    e);
            removeScheduleQuietly(link.getScheduleId());
            throw e;
        }
    }

    private void removeScheduleQuietly(String scheduleId) {
        try {
            schedulerClient.delete(scheduleId);
        } catch (DialCoreClientException e) {
            log.warn(
                    "Could not remove schedule {} from the scheduler; it may fire without being manageable: {}",
                    scheduleId,
                    e.getMessage(),
                    e);
        }
    }

    private static boolean isNotFound(DialCoreClientException e) {
        return e.getStatusCode().value() == HttpStatus.NOT_FOUND.value();
    }
}
