package com.epam.aidial.evaluation.service.domain.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Pre-configured route response")
public class RouteResponseDto {

    @Schema(description = "HTTP status code")
    private Integer status;

    @Schema(description = "Response body")
    private String body;
}
