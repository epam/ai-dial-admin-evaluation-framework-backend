package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Test case request DTOs: data default survives JSON")
class TestCaseRequestDtoDataDefaultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("TestCaseRequestDto: omitted data deserializes to an empty map")
    void requestDto_omittedData_isEmptyMap() {
        final TestCaseRequestDto dto = objectMapper.readValue("{\"testCaseName\":\"t\"}", TestCaseRequestDto.class);

        assertThat(dto.getData()).isEmpty();
    }

    @Test
    @DisplayName("TestCaseRequestDto: explicit null data deserializes to an empty map")
    void requestDto_explicitNullData_isEmptyMap() {
        final TestCaseRequestDto dto =
                objectMapper.readValue("{\"testCaseName\":\"t\",\"data\":null}", TestCaseRequestDto.class);

        assertThat(dto.getData()).isEmpty();
    }

    @Test
    @DisplayName("TestCaseRequestDto: explicit data is preserved")
    void requestDto_explicitData_isPreserved() {
        final TestCaseRequestDto dto = objectMapper.readValue(
                "{\"testCaseName\":\"t\",\"data\":{\"prompt\":\"Hello\"}}", TestCaseRequestDto.class);

        assertThat(dto.getData()).containsEntry("prompt", "Hello");
    }

    @Test
    @DisplayName("TestCaseBatchPutItemDto: omitted and explicit null data deserialize to an empty map")
    void batchPutItem_omittedOrNullData_isEmptyMap() {
        final TestCaseBatchPutItemDto omitted = objectMapper.readValue(
                "{\"id\":\"550e8400-e29b-41d4-a716-446655440001\",\"testCaseName\":\"t\"}",
                TestCaseBatchPutItemDto.class);
        final TestCaseBatchPutItemDto explicitNull = objectMapper.readValue(
                "{\"id\":\"550e8400-e29b-41d4-a716-446655440001\",\"testCaseName\":\"t\",\"data\":null}",
                TestCaseBatchPutItemDto.class);

        assertThat(omitted.getData()).isEmpty();
        assertThat(explicitNull.getData()).isEmpty();
    }
}
