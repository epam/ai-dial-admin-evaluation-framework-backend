package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.model.SuiteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteRequestDto suiteType default")
class TestSuiteRequestDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("omitted suiteType in JSON deserializes to DEPLOYMENT")
    void omittedSuiteType_defaultsToDeployment() {
        final TestSuiteRequestDto dto = objectMapper.readValue("{\"name\":\"s\"}", TestSuiteRequestDto.class);

        assertThat(dto.getSuiteType()).isEqualTo(SuiteType.DEPLOYMENT);
    }

    @Test
    @DisplayName("explicit null suiteType in JSON deserializes to DEPLOYMENT")
    void explicitNullSuiteType_defaultsToDeployment() {
        final TestSuiteRequestDto dto =
                objectMapper.readValue("{\"name\":\"s\",\"suiteType\":null}", TestSuiteRequestDto.class);

        assertThat(dto.getSuiteType()).isEqualTo(SuiteType.DEPLOYMENT);
    }

    @Test
    @DisplayName("explicit suiteType in JSON is preserved")
    void explicitSuiteType_isPreserved() {
        final TestSuiteRequestDto dto =
                objectMapper.readValue("{\"name\":\"s\",\"suiteType\":\"ROUTE\"}", TestSuiteRequestDto.class);

        assertThat(dto.getSuiteType()).isEqualTo(SuiteType.ROUTE);
    }

    @Test
    @DisplayName("no-args constructor and builder default suiteType to DEPLOYMENT")
    void constructorAndBuilder_defaultToDeployment() {
        assertThat(new TestSuiteRequestDto().getSuiteType()).isEqualTo(SuiteType.DEPLOYMENT);
        assertThat(TestSuiteRequestDto.builder().build().getSuiteType()).isEqualTo(SuiteType.DEPLOYMENT);
    }
}
