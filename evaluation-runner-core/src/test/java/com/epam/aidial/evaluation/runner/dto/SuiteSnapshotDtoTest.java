package com.epam.aidial.evaluation.runner.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SuiteSnapshotDto")
class SuiteSnapshotDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("JSON missing snapshotVersion deserializes with @Builder.Default CURRENT_VERSION")
    void missingSnapshotVersionDefaultsToCurrent() throws JacksonException {
        String json = "{\"suiteType\":\"DEPLOYMENT\"}";

        SuiteSnapshotDto dto = objectMapper.readValue(json, SuiteSnapshotDto.class);

        assertThat(dto.getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
        assertThat(SuiteSnapshotDto.CURRENT_VERSION).isEqualTo("2");
        assertThat(dto.getSuiteType()).isEqualTo("DEPLOYMENT");
        assertThat(dto.getDatasetRef()).isNull();
    }

    @Test
    @DisplayName("JSON written before the chain fields exist deserializes to an empty chain, snapshotVersion still 2")
    void legacyJsonWithoutChainFieldsDeserializesToEmptyChain() throws JacksonException {
        String json = "{\"snapshotVersion\":\"2\",\"suiteType\":\"DEPLOYMENT\"}";

        SuiteSnapshotDto dto = objectMapper.readValue(json, SuiteSnapshotDto.class);

        assertThat(dto.getSnapshotVersion()).isEqualTo("2");
        assertThat(dto.getAdditionalRequests()).isNotNull().isEmpty();
        assertThat(dto.getRequestName()).isNull();
    }

    @Test
    @DisplayName("JSON carrying the chain fields round-trips additionalRequests and requestName")
    void jsonWithChainFieldsRoundTrips() throws JacksonException {
        SuiteSnapshotDto original = SuiteSnapshotDto.builder()
                .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                .suiteType("DEPLOYMENT")
                .requestName("configure")
                .additionalRequests(
                        List.of(RequestDefinitionDto.builder().name("second").build()))
                .build();

        String json = objectMapper.writeValueAsString(original);
        SuiteSnapshotDto dto = objectMapper.readValue(json, SuiteSnapshotDto.class);

        assertThat(dto.getRequestName()).isEqualTo("configure");
        assertThat(dto.getAdditionalRequests()).hasSize(1);
        assertThat(dto.getAdditionalRequests().get(0).getName()).isEqualTo("second");
    }
}
