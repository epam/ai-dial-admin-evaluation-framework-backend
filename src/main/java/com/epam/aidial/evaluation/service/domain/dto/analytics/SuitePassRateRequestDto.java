package com.epam.aidial.evaluation.service.domain.dto.analytics;

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
@Schema(description = "Request body for the suite pass-rate summary.")
public class SuitePassRateRequestDto {

    @Schema(
            description = "Test suite ids to summarise. Must be non-empty and hold at most "
                    + "ValidationConstants.MAX_BATCH_SUITE_IDS distinct ids; duplicates are ignored. Ids that "
                    + "do not belong to a suite, or have no scored run, are skipped without an error.",
            example = "[\"64ab7f1f-4f93-4aa5-a035-01ded741a6aa\", \"b5167912-1414-4056-815b-491e20111c64\"]")
    private List<UUID> suiteIds;
}
