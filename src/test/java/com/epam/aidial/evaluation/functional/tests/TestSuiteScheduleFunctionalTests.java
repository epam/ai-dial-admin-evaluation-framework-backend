package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteScheduleRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.functional.support.SchedulerTestServer;
import com.epam.aidial.evaluation.functional.support.SchedulerTestServer.RecordedRequest;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryExecutor;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleCronDto;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleRunDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteDeleteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleResponseDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests of suite schedules against a fake DIAL Core scheduler ({@link SchedulerTestServer}) and the real
 * database. Runs in a context where {@code dial.scheduler.enabled=true} and the DIAL Core base URL points at the fake.
 */
@DisplayName("Test Suite Schedule Functional Tests")
public abstract class TestSuiteScheduleFunctionalTests extends BaseFunctionalTest {

    private static final String CALLBACK_BASE_URL = "http://ef-backend.test:8080";
    private static final long RUN_SETTLE_TIMEOUT_MS = 30_000L;
    private static final long RUN_POLL_INTERVAL_MS = 100L;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestSuiteScheduleRepository repository;

    @Autowired
    private StructuredQueryExecutor queryExecutor;

    private UUID suiteId;

    private final List<UUID> startedRuns = new ArrayList<>();

    protected abstract SchedulerTestServer scheduler();

    @BeforeEach
    void setUp() {
        scheduler().reset();
        startedRuns.clear();
        metaTestDataHelper.cleanupTestSuiteSchedules();
        suiteId = metaTestDataHelper.createTestSuite("Schedule Suite").getId();
        // a run needs at least one valid test case in the suite's dataset
        metaTestDataHelper.seedTestCaseInDataset(metaTestDataHelper.getDatasetId(suiteId), "Case", "{}");
    }

    /**
     * Lets every run this test started finish before the shared database is reset, so a background run job cannot
     * deadlock the schema reset or leak rows into the next test.
     */
    @AfterEach
    void awaitStartedRuns() {
        startedRuns.forEach(this::awaitTerminal);
    }

    private void awaitTerminal(UUID runId) {
        final long deadline = System.currentTimeMillis() + RUN_SETTLE_TIMEOUT_MS;
        while (!isTerminal(runId) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(RUN_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isTerminal(UUID runId) {
        return metaTestDataHelper
                .findRun(runId)
                .map(run -> RunStatus.isTerminal(run.getStatus()))
                .orElse(true);
    }

    @Test
    @DisplayName("Should register the schedule in the scheduler, record the suite link and return 201")
    void shouldCreateScheduleAndRecordLink() {
        final ResponseEntity<TestSuiteScheduleResponseDto> response = create(suiteId, request("Nightly"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        final TestSuiteScheduleResponseDto created = response.getBody();
        assertThat(created.getName()).isEqualTo("Nightly");
        assertThat(created.getTestSuiteId()).isEqualTo(suiteId);
        assertThat(created.getCron().getHour()).isEqualTo("2");
        assertThat(created.getTimezone()).isEqualTo("UTC");
        assertThat(created.getRunConfig().getNumberOfRuns()).isEqualTo(2);
        assertThat(created.isPaused()).isFalse();

        final TestSuiteSchedule link = repository.findById(created.getId()).orElseThrow();
        assertThat(link.getTestSuiteId()).isEqualTo(suiteId);
        assertThat(link.getCreatedBy()).isEqualTo("anonymous");
        assertThat(link.getScheduleRef())
                .as("a callback reference is stored with the link")
                .isNotBlank();

        final JsonNode sent = scheduler().createBody();
        assertThat(sent.path("display_name").asString()).isEqualTo("Nightly");
        assertThat(sent.path("service_id").asString()).isEqualTo("dial-native");
        assertThat(sent.path("timezone").asString()).isEqualTo("UTC");
        assertThat(sent.at("/trigger/cron/fields/hour").asString()).isEqualTo("2");
        assertThat(sent.at("/properties/target_type").asString()).isEqualTo("rest");
        assertThat(sent.at("/properties/method").asString()).isEqualTo("POST");
        assertThat(sent.at("/properties/url").asString())
                .isEqualTo(CALLBACK_BASE_URL + "/api/v1/test-suites/" + suiteId + "/runs?scheduleRef="
                        + link.getScheduleRef());
        assertThat(sent.at("/properties/payload/runConfig/numberOfRuns").asInt())
                .isEqualTo(2);
        assertThat(sent.at("/properties/timeout").asInt()).isEqualTo(120);
    }

    @Test
    @DisplayName("Should forward the caller's bearer token to the scheduler")
    void shouldForwardBearerCredential() {
        createWithHeader(suiteId, request("Nightly"), HttpHeaders.AUTHORIZATION, "Bearer caller-jwt");

        final RecordedRequest sent = scheduler().requests("POST").get(0);
        assertThat(sent.authorization()).isEqualTo("Bearer caller-jwt");
        assertThat(sent.apiKey()).isNull();
    }

    @Test
    @DisplayName("Should forward an API-key caller's key to the scheduler under the Api-Key header")
    void shouldForwardApiKeyCredential() {
        createWithHeader(suiteId, request("Nightly"), "Api-Key", "caller-key");

        final RecordedRequest sent = scheduler().requests("POST").get(0);
        assertThat(sent.apiKey()).isEqualTo("caller-key");
        assertThat(sent.authorization()).isNull();
    }

    @Test
    @DisplayName("Should support the whole lifecycle: list, get, edit, pause, resume and delete")
    void shouldSupportFullLifecycle() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();

        assertThat(list(suiteId, 0, 20).getBody().getContent())
                .extracting(TestSuiteScheduleResponseDto::getId)
                .containsExactly(id);
        assertThat(get(id).getBody().getName()).isEqualTo("Nightly");

        final TestSuiteScheduleRequestDto edit = request("Weekly");
        edit.setCron(ScheduleCronDto.builder().dayOfWeek("mon").hour("6").build());
        edit.setTimezone("Europe/Berlin");
        final ResponseEntity<TestSuiteScheduleResponseDto> edited =
                exchange("/schedules/" + id, HttpMethod.PUT, jsonEntity(edit), TestSuiteScheduleResponseDto.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().getName()).isEqualTo("Weekly");
        assertThat(edited.getBody().getTimezone()).isEqualTo("Europe/Berlin");
        assertThat(edited.getBody().getCron().getDayOfWeek()).isEqualTo("mon");
        assertThat(edited.getBody().getTestSuiteId()).isEqualTo(suiteId);
        final JsonNode sentUpdate = parse(scheduler().requests("PUT").get(0).body());
        assertThat(sentUpdate.at("/properties/url").asString())
                .as("edit keeps the schedule's reference in the callback URL")
                .isEqualTo(CALLBACK_BASE_URL + "/api/v1/test-suites/" + suiteId + "/runs?scheduleRef="
                        + repository.findById(id).orElseThrow().getScheduleRef());

        final ResponseEntity<TestSuiteScheduleResponseDto> paused =
                exchange("/schedules/" + id + "/pause", HttpMethod.POST, null, TestSuiteScheduleResponseDto.class);
        assertThat(paused.getBody().isPaused()).isTrue();
        assertThat(paused.getBody().getNextRunTime()).isNull();
        assertThat(get(id).getBody().isPaused()).isTrue();

        final ResponseEntity<TestSuiteScheduleResponseDto> resumed =
                exchange("/schedules/" + id + "/resume", HttpMethod.POST, null, TestSuiteScheduleResponseDto.class);
        assertThat(resumed.getBody().isPaused()).isFalse();
        assertThat(resumed.getBody().getNextRunTime()).isNotNull();

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(repository.findById(id)).isEmpty();
        assertThat(scheduler().contains(id)).isFalse();
        assertThat(get(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should answer 404 without calling the scheduler for a schedule EF did not create")
    void shouldRejectScheduleWithoutLink() {
        final String foreignId = scheduler().seed("created elsewhere");

        assertThat(get(foreignId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/schedules/" + foreignId + "/pause", HttpMethod.POST, null, String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(foreignId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // seed() bypasses HTTP, so any recorded request would have come from EF
        assertThat(scheduler().requests()).isEmpty();
        assertThat(scheduler().contains(foreignId)).isTrue();
    }

    @Test
    @DisplayName("Should answer 404 and not call the scheduler when the suite does not exist")
    void shouldRejectUnknownSuite() {
        final ResponseEntity<String> response = createRaw(UUID.randomUUID(), request("Nightly"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(scheduler().requests()).isEmpty();
    }

    @Test
    @DisplayName("Should answer 400 and not call the scheduler for an empty cron")
    void shouldRejectEmptyCron() {
        final TestSuiteScheduleRequestDto request = request("Nightly");
        request.setCron(new ScheduleCronDto());

        assertThat(createRaw(suiteId, request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(scheduler().requests()).isEmpty();
    }

    @Test
    @DisplayName("Should answer 400 and not call the scheduler for an unknown timezone")
    void shouldRejectInvalidTimezone() {
        final TestSuiteScheduleRequestDto request = request("Nightly");
        request.setTimezone("Mars/Olympus");

        assertThat(createRaw(suiteId, request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(scheduler().requests()).isEmpty();
    }

    @Test
    @DisplayName("Should answer 400 and not call the scheduler when numberOfRuns exceeds the configured maximum")
    void shouldRejectTooManyRuns() {
        final TestSuiteScheduleRequestDto request = request("Nightly");
        request.setRunConfig(RunConfigDto.builder().numberOfRuns(65).build());

        assertThat(createRaw(suiteId, request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(scheduler().requests()).isEmpty();
    }

    @Test
    @DisplayName("Should answer 502 and leave no link when the scheduler fails the create call")
    void shouldMapSchedulerFailureAndRecordNothing() {
        scheduler().failNext(503);

        final ResponseEntity<String> response = createRaw(suiteId, request("Nightly"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(scheduler().requests("POST")).hasSize(1);
        assertThat(repository
                        .findPageByTestSuiteId(suiteId, PageRequest.of(0, 10))
                        .getTotalElements())
                .isZero();
    }

    @Test
    @DisplayName("Should pass a scheduler 403 through as 403")
    void shouldPassThroughForbidden() {
        scheduler().failNext(403);

        assertThat(createRaw(suiteId, request("Nightly")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Should keep the schedule in the scheduler but drop the link when the suite is deleted")
    void shouldDropLinkButNotScheduleWhenSuiteDeleted() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();

        final ResponseEntity<TestSuiteDeleteResponseDto> deleted = restTemplate.exchange(
                apiUrl("/test-suites/" + suiteId), HttpMethod.DELETE, null, TestSuiteDeleteResponseDto.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repository.findById(id)).isEmpty();
        assertThat(scheduler().contains(id)).isTrue();
    }

    @Test
    @DisplayName("Should page only the requested suite's schedules with totals")
    void shouldPageSchedulesOfTheSuiteOnly() {
        final Set<String> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            ids.add(create(suiteId, request("Schedule " + i)).getBody().getId());
        }
        final UUID otherSuite =
                metaTestDataHelper.createTestSuite("Other Suite").getId();
        create(otherSuite, request("Elsewhere"));

        final PageResponseDto<TestSuiteScheduleResponseDto> first =
                list(suiteId, 0, 2).getBody();
        final PageResponseDto<TestSuiteScheduleResponseDto> second =
                list(suiteId, 1, 2).getBody();

        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);
        assertThat(first.getTotalElements()).isEqualTo(3);
        final Set<String> listed = new HashSet<>();
        first.getContent().forEach(s -> listed.add(s.getId()));
        second.getContent().forEach(s -> listed.add(s.getId()));
        assertThat(listed).isEqualTo(ids);
    }

    @Test
    @DisplayName("Should omit from the list a schedule that was removed from the scheduler outside EF")
    void shouldOmitExternallyRemovedScheduleFromList() {
        final String kept = create(suiteId, request("Kept")).getBody().getId();
        final String lost = create(suiteId, request("Lost")).getBody().getId();
        scheduler().forget(lost);

        final PageResponseDto<TestSuiteScheduleResponseDto> page =
                list(suiteId, 0, 20).getBody();

        assertThat(page.getContent())
                .extracting(TestSuiteScheduleResponseDto::getId)
                .containsExactly(kept);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should treat a schedule the scheduler reports as deleted as not found")
    void shouldTreatSoftDeletedScheduleAsNotFound() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        scheduler().softDelete(id);

        assertThat(get(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(list(suiteId, 0, 20).getBody().getContent()).isEmpty();
    }

    @Test
    @DisplayName("Should remove the link and answer 204 when the scheduler has already lost the schedule")
    void shouldDeleteLinkWhenSchedulerAlreadyLostSchedule() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        scheduler().forget(id);

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("Should keep the link when deleting fails in the scheduler for another reason")
    void shouldKeepLinkWhenSchedulerDeleteFails() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        scheduler().failNext(500);

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(repository.findById(id)).isPresent();
        assertThat(scheduler().contains(id)).isTrue();
    }

    @Test
    @DisplayName("Should return the scheduler's fire history with epoch-millisecond timestamps and paging")
    void shouldListFireHistory() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        final Instant start = Instant.parse("2026-11-25T10:00:00Z");
        scheduler().addRun(id, "success", start, start.plusSeconds(3));
        scheduler().addRun(id, "in_progress", start.plusSeconds(60), null);
        scheduler().addRun(id, "error", start.plusSeconds(120), start.plusSeconds(121));

        final PageResponseDto<ScheduleRunDto> first = exchange(
                        "/schedules/" + id + "/runs?page=0&size=2",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<PageResponseDto<ScheduleRunDto>>() {})
                .getBody();
        final PageResponseDto<ScheduleRunDto> second = exchange(
                        "/schedules/" + id + "/runs?page=1&size=2",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<PageResponseDto<ScheduleRunDto>>() {})
                .getBody();

        assertThat(first.getContent()).extracting(ScheduleRunDto::getStatus).containsExactly("success", "in_progress");
        assertThat(first.getContent().get(0).getStartTime()).isEqualTo(start.toEpochMilli());
        assertThat(first.getContent().get(0).getEndTime())
                .isEqualTo(start.plusSeconds(3).toEpochMilli());
        assertThat(first.getContent().get(1).getEndTime()).isNull();
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(second.getContent()).extracting(ScheduleRunDto::getStatus).containsExactly("error");
    }

    @Test
    @DisplayName("Should show a fire's EF run id and the run's live status in the fire history")
    void shouldShowRunIdAndLiveStatusInFireHistory() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        final UUID runId = fire(id).getBody().getId();
        awaitTerminal(runId);
        final Instant start = Instant.parse("2026-11-25T10:00:00Z");
        scheduler().addRun(id, "success", start, start.plusSeconds(3), recordedResponse(runId));
        scheduler().addRun(id, "error", start.plusSeconds(60), start.plusSeconds(61));

        final List<ScheduleRunDto> fires = fireHistory(id);

        assertThat(fires).hasSize(2);
        final ScheduleRunDto started = fires.get(0);
        assertThat(started.getTestSuiteRunId()).isEqualTo(runId);
        assertThat(started.getTestSuiteRunStatus())
                .as("the status is EF's current one, not the PENDING recorded at fire time")
                .isEqualTo(metaTestDataHelper.findRun(runId).orElseThrow().getStatus())
                .isNotEqualTo(RunStatus.PENDING.name());
        assertThat(fires.get(1).getTestSuiteRunId()).isNull();
        assertThat(fires.get(1).getTestSuiteRunStatus()).isNull();
    }

    @Test
    @DisplayName("Should keep a fire's run id and show no status once the run was deleted")
    void shouldShowNullStatusForDeletedRun() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        final UUID runId = fire(id).getBody().getId();
        awaitTerminal(runId);
        final Instant start = Instant.parse("2026-11-25T10:00:00Z");
        scheduler().addRun(id, "success", start, start.plusSeconds(3), recordedResponse(runId));
        metaTestDataHelper.deleteRun(runId);

        final ScheduleRunDto fire = fireHistory(id).get(0);

        assertThat(fire.getTestSuiteRunId()).isEqualTo(runId);
        assertThat(fire.getTestSuiteRunStatus()).isNull();
    }

    @Test
    @DisplayName("Should leave only that fire's run fields empty when its detail call fails")
    void shouldIsolateFailedFireDetail() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        final UUID runId = fire(id).getBody().getId();
        awaitTerminal(runId);
        final Instant start = Instant.parse("2026-11-25T10:00:00Z");
        final String broken = scheduler().addRun(id, "success", start, start.plusSeconds(3), recordedResponse(runId));
        scheduler().addRun(id, "success", start.plusSeconds(60), start.plusSeconds(63), recordedResponse(runId));
        scheduler().failRunDetail(broken);

        final List<ScheduleRunDto> fires = fireHistory(id);

        assertThat(fires).hasSize(2);
        assertThat(fires.get(0).getTestSuiteRunId()).isNull();
        assertThat(fires.get(0).getTestSuiteRunStatus()).isNull();
        assertThat(fires.get(1).getTestSuiteRunId()).isEqualTo(runId);
    }

    @Test
    @DisplayName("Should tag a run fired through the recorded callback URL and find it by schedule_id in the Query DSL")
    void shouldTagFiredRunAndFindItByScheduleId() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();

        final ResponseEntity<TestSuiteRunResponseDto> fired = fire(id);

        assertThat(fired.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(fired.getBody().getScheduleId()).isEqualTo(id);
        final UUID runId = fired.getBody().getId();
        assertThat(metaTestDataHelper.findRun(runId).orElseThrow().getScheduleId())
                .isEqualTo(id);
        assertThat(runIdsTaggedWith(id)).containsExactly(runId.toString());
    }

    @Test
    @DisplayName("Should accept a second fire with a fixed run name under a different suffixed name")
    void shouldSuffixScheduledRunNamesSoRepeatedFiresSucceed() {
        final TestSuiteScheduleRequestDto request = request("Nightly");
        request.setRunConfig(RunConfigDto.builder()
                .numberOfRuns(1)
                .testRunName("Nightly eval")
                .build());
        final String id = create(suiteId, request).getBody().getId();

        final ResponseEntity<TestSuiteRunResponseDto> first = fire(id);
        final ResponseEntity<TestSuiteRunResponseDto> second = fire(id);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final String firstName = metaTestDataHelper
                .findRun(first.getBody().getId())
                .orElseThrow()
                .getTestRunName();
        final String secondName = metaTestDataHelper
                .findRun(second.getBody().getId())
                .orElseThrow()
                .getTestRunName();
        assertThat(firstName).startsWith("Nightly eval #");
        assertThat(secondName).startsWith("Nightly eval #").isNotEqualTo(firstName);
    }

    @Test
    @DisplayName("Should still answer 409 for a manual run that repeats a run name")
    void shouldKeepRejectingDuplicateManualRunNames() {
        final TestSuiteRunRequestDto manual = TestSuiteRunRequestDto.builder()
                .runConfig(RunConfigDto.builder()
                        .numberOfRuns(1)
                        .testRunName("Manual eval")
                        .build())
                .build();

        final ResponseEntity<TestSuiteRunResponseDto> first =
                track(restTemplate.postForEntity(runsUrl(null), jsonEntity(manual), TestSuiteRunResponseDto.class));
        final ResponseEntity<String> second =
                restTemplate.postForEntity(runsUrl(null), jsonEntity(manual), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should create an untagged run for an unknown ref, another suite's ref, or no ref")
    void shouldCreateUntaggedRunWhenRefIsNotHonoured() {
        final UUID otherSuite =
                metaTestDataHelper.createTestSuite("Other Suite").getId();
        final String otherId =
                create(otherSuite, request("Elsewhere")).getBody().getId();
        final String otherRef = repository.findById(otherId).orElseThrow().getScheduleRef();

        final ResponseEntity<TestSuiteRunResponseDto> unknownRef =
                postRun(runsUrl(UUID.randomUUID().toString()));
        final ResponseEntity<TestSuiteRunResponseDto> foreignRef = postRun(runsUrl(otherRef));
        final ResponseEntity<TestSuiteRunResponseDto> noRef = postRun(runsUrl(null));

        assertThat(List.of(unknownRef, foreignRef, noRef))
                .extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.ACCEPTED);
        for (final ResponseEntity<TestSuiteRunResponseDto> response : List.of(unknownRef, foreignRef, noRef)) {
            assertThat(metaTestDataHelper
                            .findRun(response.getBody().getId())
                            .orElseThrow()
                            .getScheduleId())
                    .isNull();
        }
    }

    @Test
    @DisplayName("Should keep a run and its scheduleId after the schedule is deleted")
    void shouldKeepRunTagAfterScheduleDeleted() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        final UUID runId = fire(id).getBody().getId();

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(repository.findById(id)).isEmpty();
        assertThat(metaTestDataHelper.findRun(runId).orElseThrow().getScheduleId())
                .isEqualTo(id);
    }

    @Test
    @DisplayName("Should keep tagging runs with the same schedule after the schedule is edited")
    void shouldKeepTaggingAfterEdit() {
        final String id = create(suiteId, request("Nightly")).getBody().getId();
        final String refBefore = repository.findById(id).orElseThrow().getScheduleRef();
        final ResponseEntity<TestSuiteScheduleResponseDto> edited = exchange(
                "/schedules/" + id, HttpMethod.PUT, jsonEntity(request("Renamed")), TestSuiteScheduleResponseDto.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<TestSuiteRunResponseDto> fired = fire(id);

        assertThat(repository.findById(id).orElseThrow().getScheduleRef()).isEqualTo(refBefore);
        assertThat(fired.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(fired.getBody().getScheduleId()).isEqualTo(id);
    }

    /** What the scheduler records for a fire that reached EF: the run response body and its status code. */
    private JsonNode recordedResponse(UUID runId) {
        return parse("{\"body\":{\"id\":\"" + runId + "\",\"status\":\"PENDING\"},\"status_code\":202}");
    }

    private List<ScheduleRunDto> fireHistory(String scheduleId) {
        final ResponseEntity<PageResponseDto<ScheduleRunDto>> response = exchange(
                "/schedules/" + scheduleId + "/runs?page=0&size=20",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<PageResponseDto<ScheduleRunDto>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().getContent();
    }

    /** Calls EF the way the scheduler does: the schedule's stored callback URL with its stored payload. */
    private ResponseEntity<TestSuiteRunResponseDto> fire(String scheduleId) {
        final JsonNode properties =
                scheduler().schedule(scheduleId).orElseThrow().path("properties");
        final String url = properties.path("url").asString().replace(CALLBACK_BASE_URL, baseUrl());
        return track(restTemplate.postForEntity(
                url, jsonEntity(properties.path("payload").toString()), TestSuiteRunResponseDto.class));
    }

    private ResponseEntity<TestSuiteRunResponseDto> postRun(String url) {
        return track(restTemplate.postForEntity(
                url,
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class));
    }

    private ResponseEntity<TestSuiteRunResponseDto> track(ResponseEntity<TestSuiteRunResponseDto> response) {
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            startedRuns.add(response.getBody().getId());
        }
        return response;
    }

    private String runsUrl(String scheduleRef) {
        final String url = apiUrl("/test-suites/" + suiteId + "/runs");
        return scheduleRef == null ? url : url + "?scheduleRef=" + scheduleRef;
    }

    private List<String> runIdsTaggedWith(String scheduleId) {
        final StructuredQuery query = new StructuredQuery(
                "test_suite_runs",
                new ComparisonNode(
                        ComparisonOp.EQ,
                        List.of(new FieldExpr("schedule_id"), new ValueExpr(ValueType.STRING, scheduleId))),
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("id"), null)),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));
        return queryExecutor.execute(query).rows().stream()
                .map(row -> row.get("id").toString())
                .toList();
    }

    private TestSuiteScheduleRequestDto request(String name) {
        return TestSuiteScheduleRequestDto.builder()
                .name(name)
                .cron(ScheduleCronDto.builder().hour("2").minute("0").build())
                .runConfig(RunConfigDto.builder().numberOfRuns(2).build())
                .build();
    }

    private ResponseEntity<TestSuiteScheduleResponseDto> create(UUID suite, TestSuiteScheduleRequestDto body) {
        return exchange(
                "/test-suites/" + suite + "/schedules",
                HttpMethod.POST,
                jsonEntity(body),
                TestSuiteScheduleResponseDto.class);
    }

    private ResponseEntity<String> createRaw(UUID suite, TestSuiteScheduleRequestDto body) {
        return exchange("/test-suites/" + suite + "/schedules", HttpMethod.POST, jsonEntity(body), String.class);
    }

    private void createWithHeader(UUID suite, TestSuiteScheduleRequestDto body, String header, String value) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(header, value);
        exchange(
                "/test-suites/" + suite + "/schedules",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                TestSuiteScheduleResponseDto.class);
    }

    private ResponseEntity<TestSuiteScheduleResponseDto> get(String scheduleId) {
        return exchange("/schedules/" + scheduleId, HttpMethod.GET, null, TestSuiteScheduleResponseDto.class);
    }

    private ResponseEntity<String> delete(String scheduleId) {
        return exchange("/schedules/" + scheduleId, HttpMethod.DELETE, null, String.class);
    }

    private ResponseEntity<PageResponseDto<TestSuiteScheduleResponseDto>> list(UUID suite, int page, int size) {
        return exchange(
                "/test-suites/" + suite + "/schedules?page=" + page + "&size=" + size,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, HttpEntity<?> entity, Class<T> type) {
        return restTemplate.exchange(apiUrl(path), method, entity, type);
    }

    private <T> ResponseEntity<T> exchange(
            String path, HttpMethod method, HttpEntity<?> entity, ParameterizedTypeReference<T> type) {
        return restTemplate.exchange(apiUrl(path), method, entity, type);
    }

    private static JsonNode parse(String json) {
        return new ObjectMapper().readTree(json);
    }
}
