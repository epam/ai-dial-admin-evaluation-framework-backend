package com.epam.aidial.evaluation.service.domain.dto.testcase.bulk;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Composite bulk-patch request. At least one of `bulkOperations` or `itemOperations` "
                        + "MUST be non-empty. Bulk operations are applied first (in array order) via SQL UPDATE; "
                        + "item operations are then applied (in array order) via merge-patch on the already-bulk-updated state.")
public class TestCaseBulkPatchRequestDto {

    @Valid
    @Schema(description = "Selector-scoped homogeneous operations. Optional.")
    private List<TestCaseBulkOperationDto> bulkOperations;

    @Valid
    @Schema(description = "Per-row heterogeneous merge-patch operations. Optional.")
    private List<TestCaseItemOperationDto> itemOperations;
}
