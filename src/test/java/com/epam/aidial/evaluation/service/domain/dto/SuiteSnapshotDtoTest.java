package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SuiteSnapshotDto")
class SuiteSnapshotDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("JSON missing snapshotVersion deserializes with @Builder.Default CURRENT_VERSION")
    void missingSnapshotVersionDefaultsToCurrent() throws JsonProcessingException {
        String json = "{\"suiteType\":\"DEPLOYMENT\"}";

        SuiteSnapshotDto dto = objectMapper.readValue(json, SuiteSnapshotDto.class);

        assertThat(dto.getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
        assertThat(SuiteSnapshotDto.CURRENT_VERSION).isEqualTo("2");
        assertThat(dto.getSuiteType()).isEqualTo("DEPLOYMENT");
        assertThat(dto.getDatasetRef()).isNull();
    }
}
