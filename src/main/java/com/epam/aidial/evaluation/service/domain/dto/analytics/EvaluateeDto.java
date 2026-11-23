package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "What a test suite evaluates, derived from the suite's target reference as it is now. Each"
                + " field is omitted when the reference does not carry it.")
public class EvaluateeDto {

    @Schema(description = "Deployment id (DEPLOYMENT and MCP_TOOL suites); absent for ROUTE suites", example = "gpt-4o")
    private String id;

    @Schema(description = "Display name; for ROUTE suites the route name", example = "GPT-4o")
    private String name;

    @Schema(
            description = "The reference's own deployment type value, unmapped (for example dial-model,"
                    + " dial-application, dial-toolset); absent for ROUTE suites",
            example = "dial-model")
    private String type;

    @Schema(description = "Tool name; present for MCP_TOOL suites only", example = "search")
    private String toolName;
}
