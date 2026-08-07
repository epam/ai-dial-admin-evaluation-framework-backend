package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DurableWarningMerger")
class DurableWarningMergerTest {

    private DurableWarningMerger merger;

    @BeforeEach
    void setUp() {
        merger = new DurableWarningMerger(new ValidationWarningsSerializer(new ObjectMapper()));
    }

    @Test
    @DisplayName(
            "A stored SOURCE_CONFLICT warning is preserved and invalidates a recomputation that would otherwise be valid")
    void preservesStoredSourceConflict() {
        ValidationWarningDto sourceConflict = ValidationWarningDto.builder()
                .message("Duplicate turnIndex within multi-turn case 'dup'")
                .code(ValidationWarningCode.SOURCE_CONFLICT)
                .build();
        String storedJson =
                new ValidationWarningsSerializer(new ObjectMapper()).serializeWarnings(List.of(sourceConflict));
        ValidationResult recomputed =
                ValidationResult.builder().valid(true).warnings(List.of()).build();

        ValidationResult merged = merger.merge(recomputed, storedJson);

        assertThat(merged.isValid()).isFalse();
        assertThat(merged.getWarnings()).containsExactly(sourceConflict);
    }

    @Test
    @DisplayName("No-op when no SOURCE_CONFLICT warning is stored (null storedWarningsJson)")
    void noOpWhenStoredWarningsIsNull() {
        ValidationResult recomputed = ValidationResult.builder()
                .valid(false)
                .warnings(List.of(ValidationWarningDto.builder()
                        .message("unrelated")
                        .code(ValidationWarningCode.TYPE)
                        .build()))
                .build();

        ValidationResult merged = merger.merge(recomputed, null);

        assertThat(merged.isValid()).isEqualTo(recomputed.isValid());
        assertThat(merged.getWarnings()).isEqualTo(recomputed.getWarnings());
    }

    @Test
    @DisplayName("No-op when stored warnings JSON is blank")
    void noOpWhenStoredWarningsIsBlank() {
        ValidationResult recomputed =
                ValidationResult.builder().valid(true).warnings(List.of()).build();

        ValidationResult merged = merger.merge(recomputed, "   ");

        assertThat(merged.isValid()).isTrue();
        assertThat(merged.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("No-op when stored warnings JSON is unreadable")
    void noOpWhenStoredWarningsIsUnreadable() {
        ValidationResult recomputed =
                ValidationResult.builder().valid(true).warnings(List.of()).build();

        ValidationResult merged = merger.merge(recomputed, "{not valid json");

        assertThat(merged.isValid()).isTrue();
        assertThat(merged.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("No-op when stored warnings contain only non-SOURCE_CONFLICT codes")
    void noOpWhenStoredWarningsHaveOtherCodes() {
        String storedJson = new ValidationWarningsSerializer(new ObjectMapper())
                .serializeWarnings(List.of(ValidationWarningDto.builder()
                        .message("unknown field")
                        .code(ValidationWarningCode.ADDITIONAL)
                        .build()));
        ValidationResult recomputed =
                ValidationResult.builder().valid(true).warnings(List.of()).build();

        ValidationResult merged = merger.merge(recomputed, storedJson);

        assertThat(merged.isValid()).isTrue();
        assertThat(merged.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("No duplication when the same SOURCE_CONFLICT warning was also recomputed")
    void noDuplicationWhenSameWarningRecomputed() {
        ValidationWarningDto sourceConflict = ValidationWarningDto.builder()
                .message("Duplicate turnIndex within multi-turn case 'dup'")
                .code(ValidationWarningCode.SOURCE_CONFLICT)
                .build();
        String storedJson =
                new ValidationWarningsSerializer(new ObjectMapper()).serializeWarnings(List.of(sourceConflict));
        ValidationResult recomputed = ValidationResult.builder()
                .valid(false)
                .warnings(List.of(sourceConflict))
                .build();

        ValidationResult merged = merger.merge(recomputed, storedJson);

        assertThat(merged.isValid()).isFalse();
        assertThat(merged.getWarnings()).containsExactly(sourceConflict);
    }

    @Test
    @DisplayName("Additional recomputed warnings are kept alongside the preserved SOURCE_CONFLICT warning")
    void keepsRecomputedWarningsAlongsidePreserved() {
        ValidationWarningDto sourceConflict = ValidationWarningDto.builder()
                .message("Shared column values differ across turns of case 'dup'")
                .code(ValidationWarningCode.SOURCE_CONFLICT)
                .build();
        ValidationWarningDto typeWarning = ValidationWarningDto.builder()
                .message("bad type")
                .code(ValidationWarningCode.TYPE)
                .build();
        String storedJson =
                new ValidationWarningsSerializer(new ObjectMapper()).serializeWarnings(List.of(sourceConflict));
        ValidationResult recomputed = ValidationResult.builder()
                .valid(false)
                .warnings(List.of(typeWarning))
                .build();

        ValidationResult merged = merger.merge(recomputed, storedJson);

        assertThat(merged.isValid()).isFalse();
        assertThat(merged.getWarnings()).containsExactlyInAnyOrder(typeWarning, sourceConflict);
    }
}
