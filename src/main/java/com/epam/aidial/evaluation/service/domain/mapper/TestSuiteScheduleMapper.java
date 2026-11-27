package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerRunListItem;
import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerScheduleResponse;
import com.epam.aidial.evaluation.constants.ScheduleConstants;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.ScheduleCronMapper;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleRunDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps the scheduler's schedule (cron, run configuration, timestamps) together with EF's mapping row (suite id,
 * author) to the REST response. {@code paused} is derived: the scheduler exposes no pause flag, only a missing
 * next fire time.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        uses = {InstantMapper.class})
public abstract class TestSuiteScheduleMapper {

    @Autowired
    protected ScheduleCronMapper cronMapper;

    @Autowired
    protected ObjectMapper objectMapper;

    @Mapping(target = "id", source = "remote.id")
    @Mapping(target = "name", source = "remote.displayName")
    @Mapping(target = "description", source = "remote.description")
    @Mapping(target = "testSuiteId", source = "link.testSuiteId")
    @Mapping(target = "cron", expression = "java(cronMapper.fromTrigger(remote.getTrigger()))")
    @Mapping(target = "timezone", source = "remote.timezone")
    @Mapping(target = "runConfig", expression = "java(readRunConfig(remote.getProperties()))")
    @Mapping(target = "paused", expression = "java(remote.getNextRunTime() == null)")
    @Mapping(target = "nextRunTime", source = "remote.nextRunTime")
    @Mapping(target = "createdBy", source = "link.createdBy")
    @Mapping(target = "createdAt", source = "remote.createdAt")
    @Mapping(target = "updatedAt", source = "remote.updatedAt")
    public abstract TestSuiteScheduleResponseDto toDto(SchedulerScheduleResponse remote, TestSuiteSchedule link);

    /** The run id and status are not on the scheduler's list item; the service fills them in afterwards. */
    @Mapping(target = "testSuiteRunId", ignore = true)
    @Mapping(target = "testSuiteRunStatus", ignore = true)
    public abstract ScheduleRunDto toRunDto(SchedulerRunListItem item);

    /** Reads {@code payload.runConfig} back from the scheduler's free-form {@code properties}; null when absent. */
    protected RunConfigDto readRunConfig(JsonNode properties) {
        if (properties == null) {
            return null;
        }
        final JsonNode runConfig = properties.at("/payload/" + ScheduleConstants.RUN_CONFIG_PAYLOAD_KEY);
        if (runConfig.isMissingNode() || runConfig.isNull()) {
            return null;
        }
        return objectMapper.treeToValue(runConfig, RunConfigDto.class);
    }
}
