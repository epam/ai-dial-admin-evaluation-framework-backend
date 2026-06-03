package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.properties.grafana.GrafanaProperties;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.service.domain.GrafanaLinkBuilder;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.RunErrorDetailsDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TestSuiteRunMapper")
class TestSuiteRunMapperTest {

    private TestSuiteRunMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        GrafanaProperties grafanaProperties = new GrafanaProperties();
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        GrafanaLinkBuilder grafanaLinkBuilder = new GrafanaLinkBuilder(grafanaProperties, objectMapper, clock);
        mapper = new TestSuiteRunMapper(objectMapper, grafanaLinkBuilder);
    }

    @Test
    @DisplayName("maps all fields from entity to DTO")
    void mapsAllFieldsFromEntityToDto() throws Exception {
        UUID id = UUID.randomUUID();
        UUID testSuiteId = UUID.randomUUID();
        RunConfigDto runConfig =
                RunConfigDto.builder().numberOfRuns(3).testRunName("Test Run").build();
        String runConfigJson = objectMapper.writeValueAsString(runConfig);

        TestSuiteRun entity = TestSuiteRun.builder()
                .id(id)
                .testSuiteId(testSuiteId)
                .testRunName("Test Run")
                .status("COMPLETED")
                .runConfig(runConfigJson)
                .numberOfTestCases(10)
                .startedAt(1000L)
                .completedAt(2000L)
                .createdAt(500L)
                .updatedAt(2500L)
                .build();

        TestSuiteRunResponseDto dto = mapper.toDto(entity);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getTestSuiteId()).isEqualTo(testSuiteId);
        assertThat(dto.getTestRunName()).isEqualTo("Test Run");
        assertThat(dto.getStatus()).isEqualTo("COMPLETED");
        assertThat(dto.getRunConfig()).isNotNull();
        assertThat(dto.getRunConfig().getNumberOfRuns()).isEqualTo(3);
        assertThat(dto.getRunConfig().getTestRunName()).isEqualTo("Test Run");
        assertThat(dto.getNumberOfTestCases()).isEqualTo(10);
        assertThat(dto.getStartedAt()).isEqualTo(1000L);
        assertThat(dto.getCompletedAt()).isEqualTo(2000L);
        assertThat(dto.getCreatedAt()).isEqualTo(500L);
        assertThat(dto.getUpdatedAt()).isEqualTo(2500L);
        assertThat(dto.getGrafanaExploreUrl()).isNull();
    }

    @Test
    @DisplayName("deserializes errorDetails JSON")
    void deserializesErrorDetailsJson() throws Exception {
        RunErrorDetailsDto errorDetails = RunErrorDetailsDto.builder()
                .code("TEST_ERROR")
                .category("INTERNAL")
                .message("Something failed")
                .details(Map.of("key", "value"))
                .build();
        String errorDetailsJson = objectMapper.writeValueAsString(errorDetails);

        TestSuiteRun entity = TestSuiteRun.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testRunName("Run")
                .status("FAILED")
                .runConfig("{\"numberOfRuns\":1}")
                .numberOfTestCases(5)
                .errorMessage("Something failed")
                .errorDetails(errorDetailsJson)
                .createdAt(100L)
                .updatedAt(200L)
                .build();

        TestSuiteRunResponseDto dto = mapper.toDto(entity);

        assertThat(dto.getErrorDetails()).isNotNull();
        assertThat(dto.getErrorDetails().getCode()).isEqualTo("TEST_ERROR");
        assertThat(dto.getErrorDetails().getCategory()).isEqualTo("INTERNAL");
        assertThat(dto.getErrorDetails().getMessage()).isEqualTo("Something failed");
        assertThat(dto.getErrorDetails().getDetails()).containsEntry("key", "value");
        assertThat(dto.getErrorMessage()).isEqualTo("Something failed");
    }

    @Test
    @DisplayName("handles null optional JSONB fields")
    void handlesNullOptionalJsonbFields() {
        TestSuiteRun entity = TestSuiteRun.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testRunName("Run")
                .status("PENDING")
                .runConfig("{\"numberOfRuns\":1}")
                .numberOfTestCases(0)
                .errorDetails(null)
                .createdAt(100L)
                .updatedAt(200L)
                .build();

        TestSuiteRunResponseDto dto = mapper.toDto(entity);

        assertThat(dto.getErrorDetails()).isNull();
        assertThat(dto.getErrorMessage()).isNull();
        assertThat(dto.getStartedAt()).isNull();
        assertThat(dto.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("returns null when entity is null")
    void returnsNullWhenEntityIsNull() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    @DisplayName("throws on invalid runConfig JSON")
    void throwsOnInvalidRunConfigJson() {
        TestSuiteRun entity = TestSuiteRun.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testRunName("Run")
                .status("PENDING")
                .runConfig("not valid json{{{")
                .numberOfTestCases(0)
                .createdAt(100L)
                .updatedAt(200L)
                .build();

        assertThatThrownBy(() -> mapper.toDto(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to deserialize runConfig");
    }

    @Test
    @DisplayName("populates grafanaExploreUrl when Grafana is enabled and run has started")
    void populatesGrafanaExploreUrlWhenEnabled() throws Exception {
        GrafanaProperties enabledProps = new GrafanaProperties();
        enabledProps.setBaseUrl("http://grafana:3000");
        enabledProps.setTempoDatasourceUid("tempo");
        enabledProps.setOrgId(1);
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        GrafanaLinkBuilder enabledLinkBuilder = new GrafanaLinkBuilder(enabledProps, objectMapper, clock);
        TestSuiteRunMapper enabledMapper = new TestSuiteRunMapper(objectMapper, enabledLinkBuilder);

        UUID id = UUID.randomUUID();
        RunConfigDto runConfig =
                RunConfigDto.builder().numberOfRuns(1).testRunName("Run").build();
        TestSuiteRun entity = TestSuiteRun.builder()
                .id(id)
                .testSuiteId(UUID.randomUUID())
                .testRunName("Run")
                .status("COMPLETED")
                .runConfig(objectMapper.writeValueAsString(runConfig))
                .numberOfTestCases(5)
                .startedAt(1000L)
                .completedAt(2000L)
                .createdAt(500L)
                .updatedAt(2500L)
                .build();

        TestSuiteRunResponseDto dto = enabledMapper.toDto(entity);

        assertThat(dto.getGrafanaExploreUrl())
                .isNotNull()
                .startsWith("http://grafana:3000/explore?")
                .contains("eval.run.id");
    }

    @Test
    @DisplayName("throws on invalid errorDetails JSON")
    void throwsOnInvalidErrorDetailsJson() {
        TestSuiteRun entity = TestSuiteRun.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testRunName("Run")
                .status("FAILED")
                .runConfig("{\"numberOfRuns\":1}")
                .numberOfTestCases(0)
                .errorDetails("invalid json!!")
                .createdAt(100L)
                .updatedAt(200L)
                .build();

        assertThatThrownBy(() -> mapper.toDto(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to deserialize errorDetails");
    }
}
