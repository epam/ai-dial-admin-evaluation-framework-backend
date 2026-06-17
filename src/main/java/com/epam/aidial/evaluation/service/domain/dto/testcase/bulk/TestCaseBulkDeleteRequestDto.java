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
@Schema(description = "Request body for bulk deletion of test cases by explicit UUID list.")
public class TestCaseBulkDeleteRequestDto {

    @Schema(description = "UUIDs of test cases to delete. Must be non-empty with no nulls or duplicates.")
    private List<UUID> ids;
}
