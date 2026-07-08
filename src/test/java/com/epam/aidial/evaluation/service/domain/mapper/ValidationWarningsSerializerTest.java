package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.analytics.ExtractionWarningDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ValidationWarningsSerializer")
class ValidationWarningsSerializerTest {

    private final ValidationWarningsSerializer serializer = new ValidationWarningsSerializer(new ObjectMapper());

    @Test
    @DisplayName("round-trips an extraction warning preserving its stepIndex")
    void roundTripPreservesStepIndex() {
        final ExtractionWarningDto warning = ExtractionWarningDto.builder()
                .column("answer")
                .expression("$.choices[0]")
                .error("boom")
                .stepIndex(2)
                .build();

        final String json = serializer.serializeExtractionWarnings(List.of(warning));
        final List<ExtractionWarningDto> restored = serializer.deserializeExtractionWarnings(json);

        assertThat(restored).singleElement().satisfies(w -> {
            assertThat(w.getColumn()).isEqualTo("answer");
            assertThat(w.getExpression()).isEqualTo("$.choices[0]");
            assertThat(w.getError()).isEqualTo("boom");
            assertThat(w.getStepIndex()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("omits the stepIndex key when it is null (single-step warnings unchanged)")
    void omitsNullStepIndex() {
        final ExtractionWarningDto warning = ExtractionWarningDto.builder()
                .column("answer")
                .expression("$.choices[0]")
                .error("boom")
                .build();

        final String json = serializer.serializeExtractionWarnings(List.of(warning));

        assertThat(json).doesNotContain("stepIndex");
    }

    @Test
    @DisplayName("deserializes legacy warnings without a stepIndex to a null stepIndex")
    void legacyWarningsDeserializeToNullStepIndex() {
        final String legacyJson = "[{\"column\":\"answer\",\"expression\":\"$.choices[0]\",\"error\":\"boom\"}]";

        final List<ExtractionWarningDto> restored = serializer.deserializeExtractionWarnings(legacyJson);

        assertThat(restored)
                .singleElement()
                .satisfies(w -> assertThat(w.getStepIndex()).isNull());
    }
}
