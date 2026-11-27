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
 * One fire of a schedule as returned by the scheduler's run detail endpoint. Unlike {@link SchedulerRunListItem} it
 * carries {@code result}: the target's recorded response, shaped {@code {"body": <response body>, "status_code": 202}}
 * for a fire that reached the target and null otherwise.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchedulerRunResponse {

    private String id;
    private String status;
    private Instant startTime;
    private Instant endTime;
    private JsonNode result;
    private String conversationId;
}
