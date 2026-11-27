package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleRequest;
import com.epam.aidial.evaluation.configuration.properties.scheduler.SchedulerProperties;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleCronDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("ScheduleRequestBuilder")
class ScheduleRequestBuilderTest {

    private static final UUID SUITE_ID = UUID.fromString("eab8072b-1168-4e18-9dcc-32deba20d30c");

    private SchedulerProperties properties;
    private ScheduleRequestBuilder builder;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        properties.setAppId("public/dial_scheduler__1.0.0");
        properties.setServiceId("dial-native");
        properties.setCallbackBaseUrl("http://ef-backend.dial-admin.svc.cluster.local:80");
        properties.setTimeoutSeconds(120);
        // Mirrors the application's shared ObjectMapper, which omits null properties.
        final JsonMapper objectMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();
        builder = new ScheduleRequestBuilder(properties, new ScheduleCronMapper(), objectMapper);
    }

    @Test
    @DisplayName("shouldBuildRestPostRequestToTheSuiteRunUrlWithRunConfigPayload")
    void shouldBuildRestPostRequest() {
        final SchedulerScheduleRequest request = builder.build(SUITE_ID, requestDto("Europe/Berlin"), "ref-1");

        assertThat(request.getDisplayName()).isEqualTo("Nightly");
        assertThat(request.getDescription()).isEqualTo("Every night");
        assertThat(request.getServiceId()).isEqualTo("dial-native");
        assertThat(request.getTimezone()).isEqualTo("Europe/Berlin");
        assertThat(request.getTrigger().getCron().getFields().getHour()).isEqualTo("2");
        assertThat(request.getProperties().getTargetType()).isEqualTo("rest");
        assertThat(request.getProperties().getMethod()).isEqualTo("POST");
        assertThat(request.getProperties().getUrl())
                .isEqualTo("http://ef-backend.dial-admin.svc.cluster.local:80/api/v1/test-suites/" + SUITE_ID
                        + "/runs?scheduleRef=ref-1");
        assertThat(request.getProperties().getTimeout()).isEqualTo(120);
        assertThat(request.getProperties().getPayload())
                .isEqualTo(Map.of("runConfig", Map.of("numberOfRuns", 3, "testRunName", "scheduled")));
    }

    @Test
    @DisplayName("shouldDefaultTimezoneToUtcWhenAbsentOrBlank")
    void shouldDefaultTimezoneToUtc() {
        assertThat(builder.build(SUITE_ID, requestDto(null), "ref-1").getTimezone())
                .isEqualTo("UTC");
        assertThat(builder.build(SUITE_ID, requestDto("  "), "ref-1").getTimezone())
                .isEqualTo("UTC");
    }

    @Test
    @DisplayName("shouldRejectTimezoneThatIsNotAnIanaZoneId")
    void shouldRejectInvalidTimezone() {
        assertThatThrownBy(() -> builder.build(SUITE_ID, requestDto("Mars/Olympus"), "ref-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Mars/Olympus");
    }

    @Test
    @DisplayName("shouldRejectRequestWithEmptyCron")
    void shouldRejectEmptyCron() {
        final TestSuiteScheduleRequestDto dto = requestDto("UTC");
        dto.setCron(new ScheduleCronDto());

        assertThatThrownBy(() -> builder.build(SUITE_ID, dto, "ref-1")).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("shouldNotDoubleTheSlashWhenCallbackBaseUrlEndsWithSlash")
    void shouldNormalizeTrailingSlashOfCallbackBaseUrl() {
        properties.setCallbackBaseUrl("http://ef-backend:8080/");

        assertThat(builder.runUrl(SUITE_ID, "ref-1"))
                .isEqualTo("http://ef-backend:8080/api/v1/test-suites/" + SUITE_ID + "/runs?scheduleRef=ref-1");
    }

    @Test
    @DisplayName("shouldPutTheReferenceInTheCallbackUrlOnlyAndNotInTheRunConfigPayload")
    void shouldKeepReferenceOutOfPayload() {
        final SchedulerScheduleRequest request = builder.build(SUITE_ID, requestDto("UTC"), "ref-1");

        assertThat(request.getProperties().getUrl()).endsWith("?scheduleRef=ref-1");
        assertThat(request.getProperties().getPayload().toString()).doesNotContain("ref-1");
    }

    private static TestSuiteScheduleRequestDto requestDto(String timezone) {
        return TestSuiteScheduleRequestDto.builder()
                .name("Nightly")
                .description("Every night")
                .cron(ScheduleCronDto.builder().hour("2").minute("0").build())
                .timezone(timezone)
                .runConfig(RunConfigDto.builder()
                        .numberOfRuns(3)
                        .testRunName("scheduled")
                        .build())
                .build();
    }
}
