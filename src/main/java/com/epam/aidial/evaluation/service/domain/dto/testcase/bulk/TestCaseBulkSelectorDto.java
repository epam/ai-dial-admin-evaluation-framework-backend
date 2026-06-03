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
@Schema(description = "Selector for a bulk operation. Exactly one of `ids` or `filter` MUST be provided.")
public class TestCaseBulkSelectorDto {

    @Schema(
            description = "Explicit list of test-case UUIDs scoped to the URL's test suite.",
            example = "[\"11111111-1111-1111-1111-111111111111\"," + "\"22222222-2222-2222-2222-222222222222\"]")
    private List<UUID> ids;

    @Schema(
            description = "Filter expressions reusing the test-case filter whitelist. "
                    + "An empty list matches every test case in the suite.",
            example = "[\"enabled:eq:true\"]")
    private List<String> filter;
}
