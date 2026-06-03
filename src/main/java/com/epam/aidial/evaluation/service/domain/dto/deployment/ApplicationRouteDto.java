package com.epam.aidial.evaluation.service.domain.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Application route definition")
public class ApplicationRouteDto {

    @Schema(description = "Route name")
    private String name;

    @Schema(description = "User roles with access")
    private List<String> userRoles;

    @Schema(description = "Pre-configured response if set")
    private RouteResponseDto response;

    @Schema(description = "Whether to rewrite path to upstream")
    private Boolean rewritePath;

    @Schema(description = "Path patterns to match")
    private List<String> paths;

    @Schema(description = "HTTP methods")
    private List<String> methods;

    @Schema(description = "Upstream endpoints")
    private List<RouteUpstreamDto> upstreams;

    @Schema(description = "Max retry attempts")
    private Integer maxRetryAttempts;

    @Schema(description = "Route order (lower = higher priority)")
    private Integer order;

    @Schema(description = "Required permissions")
    private List<String> permissions;

    @Schema(description = "Attachment paths")
    private RouteAttachmentPathsDto attachmentPaths;
}
