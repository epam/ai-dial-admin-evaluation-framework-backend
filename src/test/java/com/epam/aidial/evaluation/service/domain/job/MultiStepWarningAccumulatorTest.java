package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.analytics.ExtractionWarningDto;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MultiStepWarningAccumulator")
class MultiStepWarningAccumulatorTest {

    private final ValidationWarningsSerializer serializer = new ValidationWarningsSerializer(new ObjectMapper());

    private String warning(String column, String error) {
        return serializer.serializeExtractionWarnings(List.of(
                ExtractionWarningDto.builder().column(column).error(error).build()));
    }

    @Test
    @DisplayName("stamps each turn's warnings with its step index in order")
    void tagsWarningsWithStepIndex() {
        final MultiStepWarningAccumulator accumulator = new MultiStepWarningAccumulator(serializer);

        accumulator.addStep(0, warning("answer", "boom-0"));
        accumulator.addStep(1, warning("answer", "boom-1"));

        final List<ExtractionWarningDto> result = serializer.deserializeExtractionWarnings(accumulator.toJson());
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStepIndex()).isEqualTo(0);
        assertThat(result.get(0).getError()).isEqualTo("boom-0");
        assertThat(result.get(1).getStepIndex()).isEqualTo(1);
        assertThat(result.get(1).getError()).isEqualTo("boom-1");
    }

    @Test
    @DisplayName("a turn with no warnings contributes nothing")
    void emptyTurnContributesNothing() {
        final MultiStepWarningAccumulator accumulator = new MultiStepWarningAccumulator(serializer);

        accumulator.addStep(0, "[]");
        accumulator.addStep(1, warning("answer", "boom-1"));

        final List<ExtractionWarningDto> result = serializer.deserializeExtractionWarnings(accumulator.toJson());
        assertThat(result).singleElement().satisfies(w -> {
            assertThat(w.getStepIndex()).isEqualTo(1);
            assertThat(w.getError()).isEqualTo("boom-1");
        });
    }

    @Test
    @DisplayName("all warnings within one turn share that turn's step index")
    void multipleWarningsInOneTurnShareIndex() {
        final MultiStepWarningAccumulator accumulator = new MultiStepWarningAccumulator(serializer);
        final String twoWarnings = serializer.serializeExtractionWarnings(List.of(
                ExtractionWarningDto.builder().column("a").error("e1").build(),
                ExtractionWarningDto.builder().column("b").error("e2").build()));

        accumulator.addStep(3, twoWarnings);

        final List<ExtractionWarningDto> result = serializer.deserializeExtractionWarnings(accumulator.toJson());
        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(w -> assertThat(w.getStepIndex()).isEqualTo(3));
    }

    @Test
    @DisplayName("yields an empty array when no steps were accumulated")
    void noStepsYieldsEmptyArray() {
        final MultiStepWarningAccumulator accumulator = new MultiStepWarningAccumulator(serializer);

        assertThat(accumulator.toJson()).isEqualTo("[]");
    }
}
