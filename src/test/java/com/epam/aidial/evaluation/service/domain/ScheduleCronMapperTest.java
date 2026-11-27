package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.client.scheduler.dto.SchedulerCronFields;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleCronDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ScheduleCronMapper")
class ScheduleCronMapperTest {

    private final ScheduleCronMapper mapper = new ScheduleCronMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("shouldMapSetFieldsTrimmedAndLeaveUnsetFieldsNull")
    void shouldMapSetFieldsAndLeaveOthersNull() {
        final ScheduleCronDto cron = ScheduleCronDto.builder()
                .hour(" 2 ")
                .minute("*/10")
                .dayOfWeek("mon-fri")
                .build();

        final SchedulerCronFields fields = mapper.toSchedulerFields(cron);

        assertThat(fields.getHour()).isEqualTo("2");
        assertThat(fields.getMinute()).isEqualTo("*/10");
        assertThat(fields.getDayOfWeek()).isEqualTo("mon-fri");
        assertThat(fields.getYear()).isNull();
        assertThat(fields.getSecond()).isNull();
    }

    @Test
    @DisplayName("shouldRejectCronWithNoFieldSet")
    void shouldRejectEmptyCron() {
        assertThatThrownBy(() -> mapper.toSchedulerFields(new ScheduleCronDto()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("At least one cron field must be set");
    }

    @Test
    @DisplayName("shouldRejectProvidedButBlankField")
    void shouldRejectBlankField() {
        final ScheduleCronDto cron =
                ScheduleCronDto.builder().minute("5").hour("  ").build();

        assertThatThrownBy(() -> mapper.toSchedulerFields(cron))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Cron field 'hour' must not be blank");
    }

    @Test
    @DisplayName("shouldReadCronFieldsFromSchedulerTriggerIncludingNumericValues")
    void shouldReadFieldsFromTrigger() {
        final var trigger = objectMapper.readTree("{\"cron\":{\"fields\":{\"hour\":2,\"day_of_week\":\"mon\"}}}");

        final ScheduleCronDto cron = mapper.fromTrigger(trigger);

        assertThat(cron.getHour()).isEqualTo("2");
        assertThat(cron.getDayOfWeek()).isEqualTo("mon");
        assertThat(cron.getMinute()).isNull();
    }

    @Test
    @DisplayName("shouldAcceptBareFieldsObjectInTrigger")
    void shouldAcceptBareFields() {
        final var trigger = objectMapper.readTree("{\"fields\":{\"minute\":\"*\"}}");

        assertThat(mapper.fromTrigger(trigger).getMinute()).isEqualTo("*");
    }

    @Test
    @DisplayName("shouldReturnEmptyCronForNullOrUnknownTriggerShape")
    void shouldReturnEmptyCronForUnknownShape() {
        assertThat(mapper.fromTrigger(null)).isEqualTo(new ScheduleCronDto());
        assertThat(mapper.fromTrigger(objectMapper.readTree("{\"date\":\"2026-01-01T00:00:00Z\"}")))
                .isEqualTo(new ScheduleCronDto());
    }
}
