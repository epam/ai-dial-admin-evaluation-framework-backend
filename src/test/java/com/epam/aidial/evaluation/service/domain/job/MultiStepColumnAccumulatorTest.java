package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MultiStepColumnAccumulator")
class MultiStepColumnAccumulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ResponseColumnDefinitionDto column(String name) {
        return ResponseColumnDefinitionDto.builder().name(name).build();
    }

    @Test
    @DisplayName("transposes two steps into a column-major array")
    void twoStepsColumnMajor() {
        final MultiStepColumnAccumulator accumulator = new MultiStepColumnAccumulator(new JobJsonService(objectMapper));
        final List<ResponseColumnDefinitionDto> columns = List.of(column("answer"));

        accumulator.addStep(columns, "{\"answer\":\"Paris\"}");
        accumulator.addStep(columns, "{\"answer\":\"Tokyo\"}");

        assertThat(accumulator.toJson()).isEqualTo("{\"answer\":[\"Paris\",\"Tokyo\"]}");
    }

    @Test
    @DisplayName("keeps indices aligned by inserting null for a step missing a column")
    void perStepNullAlignment() {
        final MultiStepColumnAccumulator accumulator = new MultiStepColumnAccumulator(new JobJsonService(objectMapper));
        final List<ResponseColumnDefinitionDto> columns = List.of(column("answer"), column("score"));

        accumulator.addStep(columns, "{\"answer\":\"Paris\",\"score\":0.9}");
        accumulator.addStep(columns, "{\"answer\":\"Tokyo\"}");

        assertThat(accumulator.toJson()).isEqualTo("{\"answer\":[\"Paris\",\"Tokyo\"],\"score\":[0.9,null]}");
    }

    @Test
    @DisplayName("contributes null for every column when a step's JSON is malformed")
    void malformedStepJsonYieldsNull() {
        final MultiStepColumnAccumulator accumulator = new MultiStepColumnAccumulator(new JobJsonService(objectMapper));
        final List<ResponseColumnDefinitionDto> columns = List.of(column("answer"));

        accumulator.addStep(columns, "not-json");

        assertThat(accumulator.toJson()).isEqualTo("{\"answer\":[null]}");
    }

    @Test
    @DisplayName("yields an empty object when no steps were accumulated")
    void noStepsYieldsEmptyObject() {
        final MultiStepColumnAccumulator accumulator = new MultiStepColumnAccumulator(new JobJsonService(objectMapper));

        assertThat(accumulator.toJson()).isEqualTo("{}");
    }

    @Test
    @DisplayName("adds nothing when there are no response columns")
    void emptyResponseColumnsAddsNothing() {
        final MultiStepColumnAccumulator accumulator = new MultiStepColumnAccumulator(new JobJsonService(objectMapper));

        accumulator.addStep(List.of(), "{\"answer\":\"Paris\"}");

        assertThat(accumulator.toJson()).isEqualTo("{}");
    }
}
