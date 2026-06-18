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
@Schema(description = "Outcome of a bulk delete by IDs. Both lists preserve input ordering.")
public class TestCaseBulkDeleteResponseDto {

    @Schema(description = "IDs of test cases that were found in the dataset and deleted.")
    private List<UUID> deleted;

    @Schema(description = "IDs that were not found in the dataset and were skipped.")
    private List<UUID> notFound;
}
