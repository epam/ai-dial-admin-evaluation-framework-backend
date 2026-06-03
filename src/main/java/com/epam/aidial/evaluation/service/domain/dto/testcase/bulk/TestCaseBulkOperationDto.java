package com.epam.aidial.evaluation.service.domain.dto.testcase.bulk;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Selector-scoped bulk operation: applies the shared `patch` to every "
                + "test case the selector resolves to via a single SQL UPDATE.")
public class TestCaseBulkOperationDto {

    @Valid
    @NotNull
    @Schema(description = "Selector that resolves to the affected test-case ids.")
    private TestCaseBulkSelectorDto selector;

    @NotNull
    @Schema(
            description = "Patch applied to all matched rows. Keys MUST be in the bulk-patch whitelist "
                    + "(initially `{\"enabled\"}`).",
            example = "{\"enabled\":false}")
    private Map<String, Object> patch;
}
