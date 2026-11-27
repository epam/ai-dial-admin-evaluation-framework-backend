package com.epam.aidial.evaluation.client.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Body of the scheduler's create and update schedule endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchedulerScheduleRequest {

    private String displayName;
    private String serviceId;
    private String description;
    private SchedulerTrigger trigger;
    private String timezone;
    private SchedulerScheduleProperties properties;
}
