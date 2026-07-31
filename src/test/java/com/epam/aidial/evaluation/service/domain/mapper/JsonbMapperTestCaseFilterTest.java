package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("JsonbMapper testCaseFilter")
class JsonbMapperTestCaseFilterTest {

    private final JsonbMapper mapper = new JsonbMapper(new ObjectMapper(), new RunnerJsonbMapper(new ObjectMapper()));

    @Test
    @DisplayName("round-trips a filter object through write then read")
    void roundTrips() {
        Map<String, Object> filter = Map.of(
                "op",
                "co",
                "args",
                List.of(
                        Map.of("type", "field", "name", "data::tags"),
                        Map.of("type", "value", "value_type", "string", "value", "text")));

        String json = mapper.mapTestCaseFilter(filter);
        assertThat(json).isNotBlank();

        Map<String, Object> readBack = mapper.mapTestCaseFilter(json);
        assertThat(readBack).isEqualTo(filter);
    }

    @Test
    @DisplayName("null filter maps to null both ways (column stays null = no filter)")
    void nullMapsToNull() {
        assertThat(mapper.mapTestCaseFilter((Map<String, Object>) null)).isNull();
        assertThat(mapper.mapTestCaseFilter((String) null)).isNull();
        assertThat(mapper.mapTestCaseFilter("")).isNull();
    }
}
