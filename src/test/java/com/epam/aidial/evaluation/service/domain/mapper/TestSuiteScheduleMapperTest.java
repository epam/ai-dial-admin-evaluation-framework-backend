package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunListItem;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleResponse;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.service.domain.ScheduleCronMapper;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleRunDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleResponseDto;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteScheduleMapper")
class TestSuiteScheduleMapperTest {

    private static final UUID SUITE_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestSuiteScheduleMapper mapper;

    @BeforeEach
    void setUp() {
        final TestSuiteScheduleMapperImpl impl = new TestSuiteScheduleMapperImpl();
        ReflectionTestUtils.setField(impl, "instantMapper", new InstantMapper());
        impl.cronMapper = new ScheduleCronMapper();
        impl.objectMapper = objectMapper;
        mapper = impl;
    }

    @Test
    @DisplayName("shouldCombineSchedulerScheduleWithMappingRowIntoResponse")
    void shouldMapScheduleAndLink() {
        final Instant next = Instant.parse("2026-11-26T01:00:00Z");
        final SchedulerScheduleResponse remote = SchedulerScheduleResponse.builder()
                .id("sch-1")
                .displayName("Nightly")
                .description("Every night")
                .timezone("Europe/Berlin")
                .nextRunTime(next)
                .createdAt(Instant.parse("2026-11-25T10:00:00Z"))
                .updatedAt(Instant.parse("2026-11-25T11:00:00Z"))
                .trigger(objectMapper.readTree("{\"cron\":{\"fields\":{\"hour\":\"1\"}}}"))
                .properties(objectMapper.readTree("{\"url\":\"http://ef\",\"payload\":{\"runConfig\":"
                        + "{\"numberOfRuns\":4,\"testRunName\":\"scheduled\"}}}"))
                .build();
        final TestSuiteSchedule link = TestSuiteSchedule.builder()
                .scheduleId("sch-1")
                .testSuiteId(SUITE_ID)
                .createdBy("alice")
                .createdAt(1L)
                .build();

        final TestSuiteScheduleResponseDto dto = mapper.toDto(remote, link);

        assertThat(dto.getId()).isEqualTo("sch-1");
        assertThat(dto.getName()).isEqualTo("Nightly");
        assertThat(dto.getDescription()).isEqualTo("Every night");
        assertThat(dto.getTestSuiteId()).isEqualTo(SUITE_ID);
        assertThat(dto.getCreatedBy()).isEqualTo("alice");
        assertThat(dto.getTimezone()).isEqualTo("Europe/Berlin");
        assertThat(dto.getCron().getHour()).isEqualTo("1");
        assertThat(dto.getRunConfig().getNumberOfRuns()).isEqualTo(4);
        assertThat(dto.getRunConfig().getTestRunName()).isEqualTo("scheduled");
        assertThat(dto.getNextRunTime()).isEqualTo(next.toEpochMilli());
        assertThat(dto.getCreatedAt())
                .isEqualTo(Instant.parse("2026-11-25T10:00:00Z").toEpochMilli());
        assertThat(dto.getUpdatedAt())
                .isEqualTo(Instant.parse("2026-11-25T11:00:00Z").toEpochMilli());
        assertThat(dto.isPaused()).isFalse();
    }

    @Test
    @DisplayName("shouldReportPausedWithNoNextRunTimeWhenSchedulerHasNoNextFireTime")
    void shouldDerivePausedFromMissingNextRunTime() {
        final SchedulerScheduleResponse remote =
                SchedulerScheduleResponse.builder().id("sch-1").build();
        final TestSuiteSchedule link = TestSuiteSchedule.builder()
                .scheduleId("sch-1")
                .testSuiteId(SUITE_ID)
                .build();

        final TestSuiteScheduleResponseDto dto = mapper.toDto(remote, link);

        assertThat(dto.isPaused()).isTrue();
        assertThat(dto.getNextRunTime()).isNull();
        assertThat(dto.getRunConfig()).isNull();
    }

    @Test
    @DisplayName("shouldMapRunWithEpochMillisecondTimestamps")
    void shouldMapRun() {
        final SchedulerRunListItem item = SchedulerRunListItem.builder()
                .id("r1")
                .status("success")
                .startTime(Instant.parse("2026-11-25T10:00:00Z"))
                .endTime(null)
                .build();

        final ScheduleRunDto dto = mapper.toRunDto(item);

        assertThat(dto.getId()).isEqualTo("r1");
        assertThat(dto.getStatus()).isEqualTo("success");
        assertThat(dto.getStartTime())
                .isEqualTo(Instant.parse("2026-11-25T10:00:00Z").toEpochMilli());
        assertThat(dto.getEndTime()).isNull();
    }
}
