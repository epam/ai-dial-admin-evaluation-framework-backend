package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("JsonbMapper multistepInputBindings ser/deser")
class JsonbMapperMultistepTest {

    private final JsonbMapper mapper = new JsonbMapper(new ObjectMapper());

    @Test
    @DisplayName("round-trips a multi-step array-of-arrays")
    void roundTripsArrayOfArrays() {
        List<List<InputBindingDto>> steps = List.of(
                List.of(InputBindingDto.builder()
                        .templateVariable("turn")
                        .dataField("question_1")
                        .build()),
                List.of(InputBindingDto.builder()
                        .templateVariable("turn")
                        .dataField("question_2")
                        .build()));

        String json = mapper.mapMultistepInputBindings(steps);
        List<List<InputBindingDto>> parsed = mapper.mapMultistepInputBindings(json);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0)).hasSize(1);
        assertThat(parsed.get(0).get(0).getTemplateVariable()).isEqualTo("turn");
        assertThat(parsed.get(0).get(0).getDataField()).isEqualTo("question_1");
        assertThat(parsed.get(1).get(0).getDataField()).isEqualTo("question_2");
    }

    @Test
    @DisplayName("serializes null to null")
    void serializesNullToNull() {
        assertThat(mapper.mapMultistepInputBindings((List<List<InputBindingDto>>) null))
                .isNull();
    }

    @Test
    @DisplayName("deserializes null to null")
    void deserializesNullToNull() {
        assertThat(mapper.mapMultistepInputBindings((String) null)).isNull();
    }

    @Test
    @DisplayName("deserializes blank to null")
    void deserializesBlankToNull() {
        assertThat(mapper.mapMultistepInputBindings("   ")).isNull();
    }

    @Test
    @DisplayName("round-trips an empty list as an empty array")
    void roundTripsEmptyList() {
        String json = mapper.mapMultistepInputBindings(List.of());
        assertThat(json).isEqualTo("[]");
        assertThat(mapper.mapMultistepInputBindings(json)).isEmpty();
    }
}
