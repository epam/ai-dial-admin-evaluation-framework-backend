package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("JsonbMapper additionalRequests")
class JsonbMapperAdditionalRequestsTest {

    private final JsonbMapper mapper = new JsonbMapper(new ObjectMapper(), new RunnerJsonbMapper(new ObjectMapper()));

    @Test
    @DisplayName("round-trips a chain of additional requests through write then read")
    void roundTrips() {
        List<RequestDefinitionDto> chain = List.of(
                RequestDefinitionDto.builder()
                        .name("configure")
                        .requestTemplate(RequestTemplateDto.builder()
                                .urlTemplate("/v1/configure")
                                .build())
                        .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                                .name("configId")
                                .expression("$.id")
                                .build()))
                        .inputBindings(List.of(InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("expected")
                                .build()))
                        .build(),
                RequestDefinitionDto.builder()
                        .name("ask")
                        .requestTemplate(RequestTemplateDto.builder()
                                .urlTemplate("/v1/chat")
                                .build())
                        .build());

        String json = mapper.mapAdditionalRequests(chain);
        assertThat(json).isNotBlank();

        List<RequestDefinitionDto> readBack = mapper.mapAdditionalRequests(json);
        assertThat(readBack).hasSize(2);
        assertThat(readBack.get(0).getName()).isEqualTo("configure");
        assertThat(readBack.get(0).getResponseColumns())
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("configId");
        assertThat(readBack.get(0).getInputBindings()).hasSize(1);
        assertThat(readBack.get(1).getName()).isEqualTo("ask");
    }

    @Test
    @DisplayName("null list writes to '[]' (matches the NOT NULL DEFAULT '[]' column) and reads back empty")
    void nullListMapsToEmptyArray() {
        String json = mapper.mapAdditionalRequests((List<RequestDefinitionDto>) null);
        assertThat(json).isEqualTo("[]");
        assertThat(mapper.mapAdditionalRequests(json)).isEmpty();
    }

    @Test
    @DisplayName("null/blank JSON reads back as an empty list")
    void nullOrBlankJsonMapsToEmptyList() {
        assertThat(mapper.mapAdditionalRequests((String) null)).isEmpty();
        assertThat(mapper.mapAdditionalRequests("")).isEmpty();
    }

    @Test
    @DisplayName("empty list round-trips as an empty JSON array")
    void emptyListRoundTrips() {
        String json = mapper.mapAdditionalRequests(List.<RequestDefinitionDto>of());
        assertThat(json).isEqualTo("[]");
        assertThat(mapper.mapAdditionalRequests(json)).isEmpty();
    }
}
