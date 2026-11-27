package com.epam.aidial.evaluation.client.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Schedule detail returned by the scheduler. {@code trigger} and {@code properties} are free-form objects there,
 * so they stay as JSON trees and are interpreted by the service layer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchedulerScheduleResponse {

    private String id;
    private String displayName;
    private String serviceId;
    private String description;
    private Instant nextRunTime;
    private JsonNode trigger;
    private String timezone;
    private JsonNode properties;
    private Boolean isDeleted;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
