package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerCronFields;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleCronDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Converts between EF's {@link ScheduleCronDto} and the scheduler's cron fields. The request direction is the
 * single place that rejects an empty or blank cron (HTTP 400), so create and edit share the rule.
 */
@Component
@LogExecution
public class ScheduleCronMapper {

    private static final String CRON_FIELDS_POINTER = "/cron/fields";
    private static final String FIELDS_POINTER = "/fields";

    /**
     * Converts the request cron to the scheduler's fields.
     *
     * @throws ValidationException when no field is set or a provided field is blank
     */
    public SchedulerCronFields toSchedulerFields(ScheduleCronDto cron) {
        final Map<String, String> provided = providedFields(cron);
        if (provided.isEmpty()) {
            throw new ValidationException("At least one cron field must be set");
        }
        provided.forEach((name, value) -> {
            if (value.isBlank()) {
                throw new ValidationException("Cron field '" + name + "' must not be blank");
            }
        });
        return SchedulerCronFields.builder()
                .year(trimmed(cron.getYear()))
                .month(trimmed(cron.getMonth()))
                .day(trimmed(cron.getDay()))
                .week(trimmed(cron.getWeek()))
                .dayOfWeek(trimmed(cron.getDayOfWeek()))
                .hour(trimmed(cron.getHour()))
                .minute(trimmed(cron.getMinute()))
                .second(trimmed(cron.getSecond()))
                .build();
    }

    /**
     * Reads the cron fields back from the scheduler's free-form {@code trigger} object. The scheduler echoes what it
     * was given ({@code trigger.cron.fields}); a bare {@code fields} object is accepted too. Unknown shapes yield an
     * empty cron rather than failing, since the schedule itself is still valid.
     */
    public ScheduleCronDto fromTrigger(JsonNode trigger) {
        JsonNode fields = pointer(trigger, CRON_FIELDS_POINTER);
        if (fields == null) {
            fields = pointer(trigger, FIELDS_POINTER);
        }
        if (fields == null) {
            return new ScheduleCronDto();
        }
        return ScheduleCronDto.builder()
                .year(text(fields, "year"))
                .month(text(fields, "month"))
                .day(text(fields, "day"))
                .week(text(fields, "week"))
                .dayOfWeek(text(fields, "day_of_week"))
                .hour(text(fields, "hour"))
                .minute(text(fields, "minute"))
                .second(text(fields, "second"))
                .build();
    }

    private static JsonNode pointer(JsonNode root, String pointer) {
        if (root == null) {
            return null;
        }
        final JsonNode node = root.at(pointer);
        return node.isMissingNode() || node.isNull() ? null : node;
    }

    private static Map<String, String> providedFields(ScheduleCronDto cron) {
        final Map<String, String> provided = new LinkedHashMap<>();
        putIfSet(provided, "year", cron.getYear());
        putIfSet(provided, "month", cron.getMonth());
        putIfSet(provided, "day", cron.getDay());
        putIfSet(provided, "week", cron.getWeek());
        putIfSet(provided, "dayOfWeek", cron.getDayOfWeek());
        putIfSet(provided, "hour", cron.getHour());
        putIfSet(provided, "minute", cron.getMinute());
        putIfSet(provided, "second", cron.getSecond());
        return provided;
    }

    private static void putIfSet(Map<String, String> target, String name, String value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static String text(JsonNode fields, String name) {
        final JsonNode value = fields.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }
}
