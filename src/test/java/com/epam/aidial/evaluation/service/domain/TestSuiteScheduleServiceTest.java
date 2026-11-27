package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleCronDto;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleRunDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteScheduleMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("TestSuiteScheduleService")
@ExtendWith(MockitoExtension.class)
class TestSuiteScheduleServiceTest {

    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final long NOW = 1_764_072_000_000L;

    @Mock
    private DialSchedulerClient schedulerClient;

    @Mock
    private TestSuiteScheduleRepository repository;

    @Mock
    private TestSuiteService testSuiteService;

    @Mock
    private RunConfigValidator runConfigValidator;

    @Mock
    private ScheduleRequestBuilder requestBuilder;

    @Mock
    private TestSuiteScheduleMapper mapper;

    @Mock
    private AuthorResolver authorResolver;

    @Mock
    private ScheduleFireRunResolver fireRunResolver;

    @Mock
    private TestSuiteRunService testSuiteRunService;

    @Mock
    private Jwt jwt;

    private TestSuiteScheduleService service;

    @BeforeEach
    void setUp() {
        service = new TestSuiteScheduleService(
                schedulerClient,
                repository,
                testSuiteService,
                runConfigValidator,
                requestBuilder,
                mapper,
                authorResolver,
                fireRunResolver,
                testSuiteRunService,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("shouldCreateScheduleInSchedulerThenRecordLinkWithAuthorAndClockTime")
    void shouldCreateThenRecordLink() {
        final TestSuiteScheduleRequestDto request = request();
        final SchedulerScheduleRequest body = SchedulerScheduleRequest.builder().build();
        final SchedulerScheduleResponse remote = remote("sch-1");
        final TestSuiteScheduleResponseDto dto = new TestSuiteScheduleResponseDto();
        final ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        when(testSuiteService.existsById(SUITE_ID)).thenReturn(true);
        when(requestBuilder.build(eq(SUITE_ID), eq(request), refCaptor.capture()))
                .thenReturn(body);
        when(schedulerClient.create(body)).thenReturn(remote);
        when(authorResolver.getCreatedBy(jwt)).thenReturn("alice");
        when(mapper.toDto(eq(remote), any(TestSuiteSchedule.class))).thenReturn(dto);

        final TestSuiteScheduleResponseDto result = service.create(SUITE_ID, request, jwt);

        assertThat(result).isSameAs(dto);
        final ArgumentCaptor<TestSuiteSchedule> saved = ArgumentCaptor.forClass(TestSuiteSchedule.class);
        final InOrder order = inOrder(runConfigValidator, schedulerClient, repository);
        order.verify(runConfigValidator).validate(request.getRunConfig());
        order.verify(schedulerClient).create(body);
        order.verify(repository).save(saved.capture());
        assertThat(saved.getValue().getScheduleId()).isEqualTo("sch-1");
        assertThat(saved.getValue().getTestSuiteId()).isEqualTo(SUITE_ID);
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("alice");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getScheduleRef())
                .as("the reference sent in the callback URL is the one stored with the link")
                .isNotBlank()
                .isEqualTo(refCaptor.getValue());
    }

    @Test
    @DisplayName("shouldRespondNotFoundAndNotCallSchedulerWhenSuiteDoesNotExist")
    void shouldNotCallSchedulerForUnknownSuite() {
        when(testSuiteService.existsById(SUITE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(SUITE_ID, request(), jwt)).isInstanceOf(EntityNotFoundException.class);

        verifyNoInteractions(schedulerClient, repository, runConfigValidator);
    }

    @Test
    @DisplayName("shouldRejectInvalidRunConfigWithoutCallingScheduler")
    void shouldNotCallSchedulerForInvalidRunConfig() {
        final TestSuiteScheduleRequestDto request = request();
        when(testSuiteService.existsById(SUITE_ID)).thenReturn(true);
        doThrow(new ValidationException("numberOfRuns must not exceed 10"))
                .when(runConfigValidator)
                .validate(request.getRunConfig());

        assertThatThrownBy(() -> service.create(SUITE_ID, request, jwt)).isInstanceOf(ValidationException.class);

        verifyNoInteractions(schedulerClient, repository);
    }

    @Test
    @DisplayName("shouldRejectInvalidCronWithoutCallingScheduler")
    void shouldNotCallSchedulerForInvalidCron() {
        final TestSuiteScheduleRequestDto request = request();
        when(testSuiteService.existsById(SUITE_ID)).thenReturn(true);
        when(requestBuilder.build(eq(SUITE_ID), eq(request), anyString()))
                .thenThrow(new ValidationException("At least one cron field"));

        assertThatThrownBy(() -> service.create(SUITE_ID, request, jwt)).isInstanceOf(ValidationException.class);

        verifyNoInteractions(schedulerClient, repository);
    }

    @Test
    @DisplayName("shouldRemoveScheduleFromSchedulerAndRethrowWhenRecordingTheLinkFails")
    void shouldCompensateWhenLinkInsertFails() {
        stubSuccessfulSchedulerCreate("sch-1");
        final DataIntegrityViolationException failure = new DataIntegrityViolationException("boom");
        when(repository.save(any(TestSuiteSchedule.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.create(SUITE_ID, request(), jwt)).isSameAs(failure);

        verify(schedulerClient).delete("sch-1");
    }

    @Test
    @DisplayName("shouldStillRethrowTheOriginalFailureWhenTheCompensatingDeleteAlsoFails")
    void shouldRethrowOriginalWhenCompensationFails() {
        stubSuccessfulSchedulerCreate("sch-1");
        final DataIntegrityViolationException failure = new DataIntegrityViolationException("boom");
        when(repository.save(any(TestSuiteSchedule.class))).thenThrow(failure);
        doThrow(new DialCoreClientException(HttpStatus.BAD_GATEWAY, "down"))
                .when(schedulerClient)
                .delete("sch-1");

        assertThatThrownBy(() -> service.create(SUITE_ID, request(), jwt)).isSameAs(failure);
    }

    @Test
    @DisplayName("shouldRespondNotFoundWithoutCallingSchedulerForAnUnmappedScheduleId")
    void shouldRejectUnmappedScheduleId() {
        when(repository.findById("foreign")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("foreign")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.pause("foreign")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.resume("foreign")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.update("foreign", request())).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.delete("foreign")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.listRuns("foreign", 0, 10)).isInstanceOf(EntityNotFoundException.class);

        verifyNoInteractions(schedulerClient);
    }

    @Test
    @DisplayName("shouldRespondNotFoundWhenSchedulerReportsTheMappedScheduleAsDeleted")
    void shouldTreatSoftDeletedScheduleAsNotFound() {
        final TestSuiteSchedule link = link("sch-1");
        final SchedulerScheduleResponse remote = remote("sch-1");
        remote.setIsDeleted(true);
        when(repository.findById("sch-1")).thenReturn(Optional.of(link));
        when(schedulerClient.get("sch-1")).thenReturn(remote);

        assertThatThrownBy(() -> service.get("sch-1")).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("shouldPageMappingRowsAndOmitSchedulesTheSchedulerNoLongerHas")
    void shouldOmitExternallyDeletedSchedulesFromList() {
        final TestSuiteSchedule kept = link("sch-1");
        final TestSuiteSchedule gone = link("sch-2");
        final PageRequest pageRequest = PageRequest.of(0, 2);
        final SchedulerScheduleResponse remote = remote("sch-1");
        final TestSuiteScheduleResponseDto dto = new TestSuiteScheduleResponseDto();
        when(testSuiteService.existsById(SUITE_ID)).thenReturn(true);
        when(repository.findPageByTestSuiteId(SUITE_ID, pageRequest))
                .thenReturn(Page.of(List.of(kept, gone), pageRequest, 5));
        when(schedulerClient.get("sch-1")).thenReturn(remote);
        when(schedulerClient.get("sch-2")).thenThrow(new DialCoreClientException(HttpStatus.NOT_FOUND, "gone"));
        when(mapper.toDto(remote, kept)).thenReturn(dto);

        final PageResponseDto<TestSuiteScheduleResponseDto> page = service.listForSuite(SUITE_ID, 0, 2);

        assertThat(page.getContent()).containsExactly(dto);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getPage()).isZero();
        assertThat(page.getSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("shouldPropagateSchedulerFailuresOtherThanNotFoundWhenListing")
    void shouldPropagateNonNotFoundErrorsOnList() {
        final PageRequest pageRequest = PageRequest.of(0, 2);
        when(testSuiteService.existsById(SUITE_ID)).thenReturn(true);
        when(repository.findPageByTestSuiteId(SUITE_ID, pageRequest))
                .thenReturn(Page.of(List.of(link("sch-1")), pageRequest, 1));
        when(schedulerClient.get("sch-1")).thenThrow(new DialCoreClientException(HttpStatus.SERVICE_UNAVAILABLE, "x"));

        assertThatThrownBy(() -> service.listForSuite(SUITE_ID, 0, 2))
                .isInstanceOfSatisfying(
                        DialCoreClientException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    @DisplayName("shouldRespondNotFoundWhenListingSchedulesOfAnUnknownSuite")
    void shouldRejectListForUnknownSuite() {
        when(testSuiteService.existsById(SUITE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.listForSuite(SUITE_ID, 0, 10)).isInstanceOf(EntityNotFoundException.class);

        verifyNoInteractions(repository, schedulerClient);
    }

    @Test
    @DisplayName("shouldRebuildRequestForTheStoredSuiteAndReuseTheStoredReferenceWhenEditing")
    void shouldUseStoredSuiteIdAndRefOnUpdate() {
        final TestSuiteSchedule link = link("sch-1");
        link.setScheduleRef("ref-existing");
        final TestSuiteScheduleRequestDto request = request();
        final SchedulerScheduleRequest body = SchedulerScheduleRequest.builder().build();
        final SchedulerScheduleResponse remote = remote("sch-1");
        final TestSuiteScheduleResponseDto dto = new TestSuiteScheduleResponseDto();
        when(repository.findById("sch-1")).thenReturn(Optional.of(link));
        when(requestBuilder.build(SUITE_ID, request, "ref-existing")).thenReturn(body);
        when(schedulerClient.update("sch-1", body)).thenReturn(remote);
        when(mapper.toDto(remote, link)).thenReturn(dto);

        assertThat(service.update("sch-1", request)).isSameAs(dto);

        verify(runConfigValidator).validate(request.getRunConfig());
        verify(repository, never()).save(any());
        verify(repository, never()).updateRef(any(), any());
    }

    @Test
    @DisplayName("shouldGiveAScheduleCreatedBeforeTaggingAReferenceOnItsFirstEditAndStoreIt")
    void shouldAddReferenceToLegacyScheduleOnFirstEdit() {
        final TestSuiteSchedule link = link("sch-1");
        final TestSuiteScheduleRequestDto request = request();
        final SchedulerScheduleRequest body = SchedulerScheduleRequest.builder().build();
        final SchedulerScheduleResponse remote = remote("sch-1");
        final ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        when(repository.findById("sch-1")).thenReturn(Optional.of(link));
        when(requestBuilder.build(eq(SUITE_ID), eq(request), refCaptor.capture()))
                .thenReturn(body);
        when(schedulerClient.update("sch-1", body)).thenReturn(remote);
        when(mapper.toDto(remote, link)).thenReturn(new TestSuiteScheduleResponseDto());

        service.update("sch-1", request);

        assertThat(refCaptor.getValue()).isNotBlank();
        final InOrder order = inOrder(schedulerClient, repository);
        order.verify(schedulerClient).update("sch-1", body);
        order.verify(repository).updateRef("sch-1", refCaptor.getValue());
        assertThat(link.getScheduleRef()).isEqualTo(refCaptor.getValue());
    }

    @Test
    @DisplayName("shouldNotStoreAReferenceWhenTheSchedulerRejectsTheEdit")
    void shouldNotStoreReferenceWhenSchedulerRejectsEdit() {
        final TestSuiteSchedule link = link("sch-1");
        final TestSuiteScheduleRequestDto request = request();
        final SchedulerScheduleRequest body = SchedulerScheduleRequest.builder().build();
        when(repository.findById("sch-1")).thenReturn(Optional.of(link));
        when(requestBuilder.build(eq(SUITE_ID), eq(request), anyString())).thenReturn(body);
        when(schedulerClient.update("sch-1", body))
                .thenThrow(new DialCoreClientException(HttpStatus.SERVICE_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> service.update("sch-1", request)).isInstanceOf(DialCoreClientException.class);

        verify(repository, never()).updateRef(any(), any());
    }

    @Test
    @DisplayName("shouldCallSchedulerPauseAndResumeAndMapTheResult")
    void shouldPauseAndResume() {
        final TestSuiteSchedule link = link("sch-1");
        final SchedulerScheduleResponse paused = remote("sch-1");
        // Distinct (not Lombok-equal) responses, otherwise the two stubs below would overwrite each other.
        final SchedulerScheduleResponse resumed = remote("sch-1");
        resumed.setNextRunTime(Instant.parse("2026-11-26T01:00:00Z"));
        final TestSuiteScheduleResponseDto pausedDto = new TestSuiteScheduleResponseDto();
        final TestSuiteScheduleResponseDto resumedDto = new TestSuiteScheduleResponseDto();
        when(repository.findById("sch-1")).thenReturn(Optional.of(link));
        when(schedulerClient.pause("sch-1")).thenReturn(paused);
        when(schedulerClient.resume("sch-1")).thenReturn(resumed);
        when(mapper.toDto(paused, link)).thenReturn(pausedDto);
        when(mapper.toDto(resumed, link)).thenReturn(resumedDto);

        assertThat(service.pause("sch-1")).isSameAs(pausedDto);
        assertThat(service.resume("sch-1")).isSameAs(resumedDto);
    }

    @Test
    @DisplayName("shouldDeleteMappingRowEvenWhenSchedulerAlreadyLostTheSchedule")
    void shouldTolerateSchedulerNotFoundOnDelete() {
        when(repository.findById("sch-1")).thenReturn(Optional.of(link("sch-1")));
        doThrow(new DialCoreClientException(HttpStatus.NOT_FOUND, "gone"))
                .when(schedulerClient)
                .delete("sch-1");

        service.delete("sch-1");

        verify(repository).deleteById("sch-1");
    }

    @Test
    @DisplayName("shouldKeepMappingRowWhenSchedulerDeleteFailsForAnyOtherReason")
    void shouldKeepLinkWhenSchedulerDeleteFails() {
        when(repository.findById("sch-1")).thenReturn(Optional.of(link("sch-1")));
        doThrow(new DialCoreClientException(HttpStatus.INTERNAL_SERVER_ERROR, "boom"))
                .when(schedulerClient)
                .delete("sch-1");

        assertThatThrownBy(() -> service.delete("sch-1")).isInstanceOf(DialCoreClientException.class);

        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("shouldTranslatePageToSchedulerLimitAndOffsetAndReturnTotals")
    void shouldPageRunHistory() {
        final SchedulerRunListItem item =
                SchedulerRunListItem.builder().id("r1").build();
        final ScheduleRunDto dto = new ScheduleRunDto();
        when(repository.findById("sch-1")).thenReturn(Optional.of(link("sch-1")));
        when(schedulerClient.listRuns("sch-1", 5, 10))
                .thenReturn(SchedulerPage.<SchedulerRunListItem>builder()
                        .count(12)
                        .results(List.of(item))
                        .build());
        when(mapper.toRunDto(item)).thenReturn(dto);

        final PageResponseDto<ScheduleRunDto> page = service.listRuns("sch-1", 2, 5);

        assertThat(page.getContent()).containsExactly(dto);
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(5);
        assertThat(page.getTotalElements()).isEqualTo(12);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("shouldAttachRunIdAndLiveStatusToFiresThatStartedARun")
    void shouldEnrichFiresWithRunIdAndStatus() {
        final UUID runId = UUID.randomUUID();
        final SchedulerRunListItem fired =
                SchedulerRunListItem.builder().id("f1").status("success").build();
        final SchedulerRunListItem missed =
                SchedulerRunListItem.builder().id("f2").status("missed").build();
        final ScheduleRunDto firedDto = new ScheduleRunDto();
        final ScheduleRunDto missedDto = new ScheduleRunDto();
        stubRunPage(fired, missed);
        when(mapper.toRunDto(fired)).thenReturn(firedDto);
        when(mapper.toRunDto(missed)).thenReturn(missedDto);
        when(fireRunResolver.resolveRunIds(eq("sch-1"), any())).thenReturn(Map.of("f1", runId));
        when(testSuiteRunService.findStatusesByIds(any())).thenReturn(Map.of(runId, "COMPLETED"));

        service.listRuns("sch-1", 0, 10);

        assertThat(firedDto.getTestSuiteRunId()).isEqualTo(runId);
        assertThat(firedDto.getTestSuiteRunStatus()).isEqualTo("COMPLETED");
        assertThat(missedDto.getTestSuiteRunId()).isNull();
        assertThat(missedDto.getTestSuiteRunStatus()).isNull();
    }

    @Test
    @DisplayName("shouldKeepRunIdWithNullStatusWhenTheRunWasDeleted")
    void shouldShowNullStatusForDeletedRun() {
        final UUID runId = UUID.randomUUID();
        final SchedulerRunListItem fired =
                SchedulerRunListItem.builder().id("f1").status("success").build();
        final ScheduleRunDto firedDto = new ScheduleRunDto();
        stubRunPage(fired);
        when(mapper.toRunDto(fired)).thenReturn(firedDto);
        when(fireRunResolver.resolveRunIds(eq("sch-1"), any())).thenReturn(Map.of("f1", runId));
        when(testSuiteRunService.findStatusesByIds(any())).thenReturn(Map.of());

        service.listRuns("sch-1", 0, 10);

        assertThat(firedDto.getTestSuiteRunId()).isEqualTo(runId);
        assertThat(firedDto.getTestSuiteRunStatus()).isNull();
    }

    @Test
    @DisplayName("shouldNotQueryRunStatusesWhenNoFireStartedARun")
    void shouldSkipStatusQueryWithoutRuns() {
        final SchedulerRunListItem failed =
                SchedulerRunListItem.builder().id("f1").status("error").build();
        stubRunPage(failed);
        when(mapper.toRunDto(failed)).thenReturn(new ScheduleRunDto());
        when(fireRunResolver.resolveRunIds(eq("sch-1"), any())).thenReturn(Map.of());

        service.listRuns("sch-1", 0, 10);

        verifyNoInteractions(testSuiteRunService);
    }

    private void stubRunPage(SchedulerRunListItem... items) {
        when(repository.findById("sch-1")).thenReturn(Optional.of(link("sch-1")));
        when(schedulerClient.listRuns("sch-1", 10, 0))
                .thenReturn(SchedulerPage.<SchedulerRunListItem>builder()
                        .count(items.length)
                        .results(List.of(items))
                        .build());
    }

    private void stubSuccessfulSchedulerCreate(String scheduleId) {
        final SchedulerScheduleRequest body = SchedulerScheduleRequest.builder().build();
        when(testSuiteService.existsById(SUITE_ID)).thenReturn(true);
        when(requestBuilder.build(eq(SUITE_ID), any(TestSuiteScheduleRequestDto.class), anyString()))
                .thenReturn(body);
        when(schedulerClient.create(body)).thenReturn(remote(scheduleId));
        when(authorResolver.getCreatedBy(jwt)).thenReturn("alice");
    }

    private static TestSuiteScheduleRequestDto request() {
        return TestSuiteScheduleRequestDto.builder()
                .name("Nightly")
                .cron(ScheduleCronDto.builder().hour("2").build())
                .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                .build();
    }

    private static SchedulerScheduleResponse remote(String id) {
        return SchedulerScheduleResponse.builder().id(id).build();
    }

    private static TestSuiteSchedule link(String scheduleId) {
        return TestSuiteSchedule.builder()
                .scheduleId(scheduleId)
                .testSuiteId(SUITE_ID)
                .createdBy("alice")
                .createdAt(NOW)
                .build();
    }
}
