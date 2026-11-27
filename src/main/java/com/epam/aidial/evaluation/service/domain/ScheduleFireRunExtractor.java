package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads the EF run id out of the response the scheduler recorded for a fire. For a fire that reached EF the
 * recorded {@code result} is {@code {"body": <EF's 202 run response>, "status_code": 202}}, so {@code body.id} is the
 * run id. The recorded shape is only verified for that success case, so this reads defensively: the id is returned
 * only for a 2xx {@code status_code} with a {@code body.id} that is a valid UUID, and every other shape (no result,
 * an error response, a missing or non-UUID id) yields no id instead of failing.
 */
@Component
@LogExecution
public class ScheduleFireRunExtractor {

    private static final int FIRST_SUCCESS_STATUS = 200;
    private static final int FIRST_NON_SUCCESS_STATUS = 300;

    public Optional<UUID> extractRunId(JsonNode result) {
        if (result == null || !result.isObject() || !isSuccessStatus(result.path("status_code"))) {
            return Optional.empty();
        }
        final JsonNode id = result.path("body").path("id");
        if (!id.isString()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(id.asString()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean isSuccessStatus(JsonNode statusCode) {
        return statusCode.isInt()
                && statusCode.asInt() >= FIRST_SUCCESS_STATUS
                && statusCode.asInt() < FIRST_NON_SUCCESS_STATUS;
    }
}
