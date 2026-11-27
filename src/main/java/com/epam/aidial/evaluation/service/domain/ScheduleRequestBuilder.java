package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerCronTrigger;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleProperties;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleRequest;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerTrigger;
import com.epam.aidial.evaluation.configuration.properties.scheduler.SchedulerProperties;
import com.epam.aidial.evaluation.constants.ScheduleConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the scheduler's create/update request for a suite schedule: a {@code rest} POST to this backend's
 * {@code /api/v1/test-suites/{id}/runs} endpoint carrying the run configuration as payload.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ScheduleRequestBuilder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SchedulerProperties properties;
    private final ScheduleCronMapper cronMapper;
    private final ObjectMapper objectMapper;

    /**
     * Builds the scheduler request for a suite.
     *
     * @param scheduleRef the EF-generated reference put in the callback URL, resolved back to the schedule when the
     *     scheduler fires so the run can be tagged
     * @throws ValidationException for an empty or blank cron, or a timezone that is not an IANA zone id
     */
    public SchedulerScheduleRequest build(UUID suiteId, TestSuiteScheduleRequestDto request, String scheduleRef) {
        final String timezone = resolveTimezone(request.getTimezone());
        final Map<String, Object> runConfig = objectMapper.convertValue(request.getRunConfig(), MAP_TYPE);
        return SchedulerScheduleRequest.builder()
                .displayName(request.getName())
                .description(request.getDescription())
                .serviceId(properties.getServiceId())
                .timezone(timezone)
                .trigger(SchedulerTrigger.builder()
                        .cron(SchedulerCronTrigger.builder()
                                .fields(cronMapper.toSchedulerFields(request.getCron()))
                                .build())
                        .build())
                .properties(SchedulerScheduleProperties.builder()
                        .targetType(ScheduleConstants.TARGET_TYPE_REST)
                        .method(HttpMethod.POST.name())
                        .url(runUrl(suiteId, scheduleRef))
                        .payload(Map.of(ScheduleConstants.RUN_CONFIG_PAYLOAD_KEY, runConfig))
                        .timeout(properties.getTimeoutSeconds())
                        .build())
                .build();
    }

    /** The URL the scheduler calls to start a run of the suite, carrying the schedule's reference as a query parameter. */
    public String runUrl(UUID suiteId, String scheduleRef) {
        return UriComponentsBuilder.fromUriString(properties.getCallbackBaseUrl())
                .pathSegment("api", "v1", "test-suites", suiteId.toString(), "runs")
                .queryParam(ScheduleConstants.SCHEDULE_REF_PARAM, scheduleRef)
                .build()
                .toUriString();
    }

    private static String resolveTimezone(String timezone) {
        if (StringUtils.isBlank(timezone)) {
            return ScheduleConstants.DEFAULT_TIMEZONE;
        }
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw new ValidationException("Invalid timezone '" + timezone + "'; expected an IANA zone id");
        }
        return timezone;
    }
}
