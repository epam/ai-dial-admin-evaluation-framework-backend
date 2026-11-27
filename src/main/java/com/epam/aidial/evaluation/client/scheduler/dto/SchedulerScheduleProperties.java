package com.epam.aidial.evaluation.client.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Target call properties of a scheduler request (EF always uses a {@code rest} POST target). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchedulerScheduleProperties {

    private String targetType;
    private String method;
    private String url;
    private Map<String, Object> payload;
    private Integer timeout;
}
