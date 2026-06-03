package com.epam.aidial.evaluation.service.domain.dto.testcase.bulk;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Compact response with per-operation counts. Preserves input ordering.")
public class TestCaseBulkPatchResponseDto {

    @Schema(description = "Counts for each `bulkOperations` entry, ordered by input index.")
    private List<BulkResultDto> bulkResults;

    @Schema(description = "Per-row outcomes for each `itemOperations` entry, ordered by input index.")
    private List<ItemResultDto> itemResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Counts for a single bulk operation.")
    public static class BulkResultDto {

        @Schema(description = "Index of the operation in the input `bulkOperations` array.", example = "0")
        private int opIndex;

        @Schema(description = "Number of test cases the selector resolved to.", example = "1000")
        private long matched;

        @Schema(
                description = "Number of rows whose state actually changed (NULL-safe comparison; "
                        + "may be less than `matched` when the patched value already equals the existing value).",
                example = "997")
        private long updated;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Outcome for a single item operation.")
    public static class ItemResultDto {

        @Schema(
                description = "Test-case UUID from the input `itemOperations` entry.",
                example = "11111111-1111-1111-1111-111111111111")
        private UUID id;

        @Schema(description = "True if the merge patch changed at least one column.", example = "true")
        private boolean updated;
    }
}
