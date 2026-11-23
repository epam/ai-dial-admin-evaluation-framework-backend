package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        description = "Request body for the system-wide total evaluation cost over a period.",
        example = "{\"from\":1767225600000,\"to\":1769903999999,"
                + "\"deploymentIds\":[\"gpt-4o\",\"applications/public/my-app__0.0.1\"]}")
public class TotalCostRequestDto {

    @NotNull
    @Schema(description = "Range start, inclusive (epoch milliseconds)", example = "1767225600000")
    private Long from;

    @NotNull
    @Schema(description = "Range end, inclusive (epoch milliseconds)", example = "1769903999999")
    private Long to;

    @Size(max = ValidationConstants.MAX_COST_DEPLOYMENT_IDS)
    @Schema(
            description = "Optional deployment ids to restrict the total to. Omitted, null or empty means all "
                    + "deployments. Ids are matched exactly as sent, still percent-encoded as stored in the "
                    + "usage log. At most 1000 entries, none blank.",
            example = "[\"gpt-4o\", \"applications/public/my-app__0.0.1\"]")
    private List<@NotBlank String> deploymentIds;
}
