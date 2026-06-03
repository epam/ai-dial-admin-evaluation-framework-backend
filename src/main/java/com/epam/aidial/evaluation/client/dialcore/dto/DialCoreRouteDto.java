package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DialCoreRouteDto {

    private String name;
    private List<String> userRoles;
    private DialCoreRouteResponseDto response;
    private Boolean rewritePath;
    private List<String> paths;
    private List<String> methods;
    private List<DialCoreRouteUpstreamDto> upstreams;
    private Integer maxRetryAttempts;
    private Integer order;
    private List<String> permissions;
    private DialCoreAttachmentPathsDto attachmentPaths;
}
