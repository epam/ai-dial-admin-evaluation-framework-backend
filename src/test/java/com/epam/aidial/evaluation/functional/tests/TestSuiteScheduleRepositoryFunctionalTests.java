package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteScheduleRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteDeleteResponseDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@DisplayName("Test Suite Schedule Repository Functional Tests")
public abstract class TestSuiteScheduleRepositoryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestSuiteScheduleRepository repository;

    @Autowired
    private TestSuiteRunRepository runRepository;

    private UUID suiteId;
    private UUID otherSuiteId;

    @BeforeEach
    void setUp() {
        metaTestDataHelper.cleanupTestSuiteSchedules();
        suiteId = metaTestDataHelper.createTestSuite("Schedule Suite").getId();
        otherSuiteId =
                metaTestDataHelper.createTestSuite("Other Schedule Suite").getId();
    }

    @Test
    @DisplayName("Should save a mapping row and find it by schedule id")
    void shouldSaveAndFindById() {
        repository.save(schedule("sch-1", suiteId, 1_000L));

        TestSuiteSchedule found = repository.findById("sch-1").orElseThrow();

        assertThat(found.getTestSuiteId()).isEqualTo(suiteId);
        assertThat(found.getCreatedBy()).isEqualTo("alice");
        assertThat(found.getCreatedAt()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("Should return empty for an unknown schedule id")
    void shouldReturnEmptyForUnknownId() {
        assertThat(repository.findById("missing")).isEmpty();
    }

    @Test
    @DisplayName("Should reject a mapping row for a non-existent suite")
    void shouldRejectUnknownSuite() {
        TestSuiteSchedule orphan = schedule("sch-orphan", UUID.randomUUID(), 1_000L);

        assertThatThrownBy(() -> repository.save(orphan)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should page only the requested suite's rows, newest first, with totals")
    void shouldPageBySuiteNewestFirst() {
        metaTestDataHelper.createTestSuiteSchedule(suiteId, "sch-old", "alice", 1_000L);
        metaTestDataHelper.createTestSuiteSchedule(suiteId, "sch-mid", "alice", 2_000L);
        metaTestDataHelper.createTestSuiteSchedule(suiteId, "sch-new", "alice", 3_000L);
        metaTestDataHelper.createTestSuiteSchedule(otherSuiteId, "sch-other", "bob", 4_000L);

        Page<TestSuiteSchedule> first = repository.findPageByTestSuiteId(suiteId, PageRequest.of(0, 2));
        Page<TestSuiteSchedule> second = repository.findPageByTestSuiteId(suiteId, PageRequest.of(1, 2));

        assertThat(first.getContent())
                .extracting(TestSuiteSchedule::getScheduleId)
                .containsExactly("sch-new", "sch-mid");
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.isHasNext()).isTrue();
        assertThat(second.getContent())
                .extracting(TestSuiteSchedule::getScheduleId)
                .containsExactly("sch-old");
        assertThat(second.isHasNext()).isFalse();
    }

    @Test
    @DisplayName("Should delete a mapping row and report whether a row was removed")
    void shouldDeleteById() {
        metaTestDataHelper.createTestSuiteSchedule(suiteId, "sch-1", "alice", 1_000L);

        assertThat(repository.deleteById("sch-1")).isTrue();
        assertThat(repository.deleteById("sch-1")).isFalse();
        assertThat(repository.findById("sch-1")).isEmpty();
    }

    @Test
    @DisplayName("Should delete a suite's mapping rows by foreign-key cascade when the suite is deleted")
    void shouldCascadeOnSuiteDelete() {
        metaTestDataHelper.createTestSuiteSchedule(suiteId, "sch-1", "alice", 1_000L);
        metaTestDataHelper.createTestSuiteSchedule(otherSuiteId, "sch-2", "alice", 2_000L);

        var response = restTemplate.exchange(
                apiUrl("/test-suites/" + suiteId), HttpMethod.DELETE, null, TestSuiteDeleteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repository.findById("sch-1")).isEmpty();
        assertThat(repository.findById("sch-2")).isPresent();
    }

    @Test
    @DisplayName("Should store the callback reference with the schedule and find the schedule by it")
    void shouldStoreRefAndFindByRef() {
        repository.save(scheduleWithRef("sch-1", "ref-1"));

        TestSuiteSchedule found = repository.findByRef("ref-1").orElseThrow();

        assertThat(found.getScheduleId()).isEqualTo("sch-1");
        assertThat(found.getScheduleRef()).isEqualTo("ref-1");
        assertThat(found.getTestSuiteId()).isEqualTo(suiteId);
    }

    @Test
    @DisplayName("Should find nothing for an unknown, blank or null reference")
    void shouldFindNothingForUnknownBlankOrNullRef() {
        repository.save(scheduleWithRef("sch-1", "ref-1"));

        assertThat(repository.findByRef("unknown")).isEmpty();
        assertThat(repository.findByRef("")).isEmpty();
        assertThat(repository.findByRef("   ")).isEmpty();
        assertThat(repository.findByRef(null)).isEmpty();
    }

    @Test
    @DisplayName("Should reject a second schedule that reuses a callback reference")
    void shouldRejectDuplicateRef() {
        repository.save(scheduleWithRef("sch-1", "ref-1"));
        TestSuiteSchedule duplicate = scheduleWithRef("sch-2", "ref-1");

        assertThatThrownBy(() -> repository.save(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should allow any number of schedules without a reference")
    void shouldAllowManySchedulesWithoutRef() {
        repository.save(schedule("sch-1", suiteId, 1_000L));
        repository.save(schedule("sch-2", suiteId, 2_000L));

        assertThat(repository.findById("sch-1").orElseThrow().getScheduleRef()).isNull();
        assertThat(repository.findById("sch-2").orElseThrow().getScheduleRef()).isNull();
    }

    @Test
    @DisplayName("Should set the reference of a schedule that has none and report whether the schedule exists")
    void shouldUpdateRef() {
        repository.save(schedule("sch-1", suiteId, 1_000L));

        assertThat(repository.updateRef("sch-1", "ref-new")).isTrue();
        assertThat(repository.updateRef("missing", "ref-other")).isFalse();
        assertThat(repository.findByRef("ref-new").orElseThrow().getScheduleId())
                .isEqualTo("sch-1");
        assertThat(repository.findByRef("ref-other")).isEmpty();
    }

    @Test
    @DisplayName("Should read a run's schedule id back both by id and through the list query, and null for manual runs")
    void shouldRoundTripRunScheduleId() {
        UUID taggedId =
                metaTestDataHelper.createScheduledTestSuiteRun(suiteId, "sch-1").getId();
        UUID manualId =
                metaTestDataHelper.createScheduledTestSuiteRun(suiteId, null).getId();
        FilterCondition forSuite = FilterCondition.builder()
                .field("testSuiteId")
                .operator(FilterOperator.EQ)
                .rawValue(suiteId.toString())
                .build();

        Page<TestSuiteRun> listed = runRepository.findAll(PageRequest.of(0, 10), List.of(forSuite), false);

        assertThat(runRepository.findById(taggedId).orElseThrow().getScheduleId())
                .isEqualTo("sch-1");
        assertThat(runRepository.findById(manualId).orElseThrow().getScheduleId())
                .isNull();
        assertThat(listed.getContent())
                .extracting(TestSuiteRun::getId, TestSuiteRun::getScheduleId)
                .containsExactlyInAnyOrder(tuple(taggedId, "sch-1"), tuple(manualId, null));
    }

    @Test
    @DisplayName("Should keep a run's schedule id after the schedule's link row is deleted")
    void shouldKeepRunScheduleIdAfterScheduleDeleted() {
        metaTestDataHelper.createTestSuiteSchedule(suiteId, "sch-1", "alice", 1_000L);
        UUID runId =
                metaTestDataHelper.createScheduledTestSuiteRun(suiteId, "sch-1").getId();

        assertThat(repository.deleteById("sch-1")).isTrue();

        assertThat(runRepository.findById(runId).orElseThrow().getScheduleId()).isEqualTo("sch-1");
    }

    private TestSuiteSchedule scheduleWithRef(String scheduleId, String scheduleRef) {
        TestSuiteSchedule schedule = schedule(scheduleId, suiteId, 1_000L);
        schedule.setScheduleRef(scheduleRef);
        return schedule;
    }

    private static TestSuiteSchedule schedule(String scheduleId, UUID testSuiteId, long createdAt) {
        return TestSuiteSchedule.builder()
                .scheduleId(scheduleId)
                .testSuiteId(testSuiteId)
                .createdBy("alice")
                .createdAt(createdAt)
                .build();
    }
}
