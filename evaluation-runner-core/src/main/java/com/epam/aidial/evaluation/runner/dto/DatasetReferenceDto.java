package com.epam.aidial.evaluation.runner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight pointer to a {@code Dataset} embedded in immutable contexts such as
 * {@code SuiteSnapshotDto}. Carries only the fields necessary to identify which dataset
 * (and which version of it) a snapshot was taken against.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetReferenceDto {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(example = "1", description = "Optimistic-concurrency version of the dataset at the time of capture")
    private Long version;

    @Schema(example = "My Dataset")
    private String name;
}
