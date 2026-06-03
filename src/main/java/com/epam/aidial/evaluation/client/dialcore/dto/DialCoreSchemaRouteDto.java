package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DialCoreSchemaRouteDto {

    @JsonProperty("dial:paths")
    private List<String> paths;

    @JsonProperty("dial:methods")
    private List<String> methods;

    @JsonProperty("dial:upstreams")
    private List<DialCoreSchemaRouteUpstreamDto> upstreams;

    @JsonProperty("dial:userRoles")
    private List<String> userRoles;

    @JsonProperty("dial:rewritePath")
    private Boolean rewritePath;

    @JsonProperty("dial:order")
    private Integer order;

    @JsonProperty("dial:maxRetryAttempts")
    private Integer maxRetryAttempts;

    @JsonProperty("dial:permissions")
    private List<String> permissions;

    @JsonProperty("dial:attachmentPaths")
    private DialCoreSchemaAttachmentPathsDto attachmentPaths;

    @JsonProperty("dial:response")
    private DialCoreSchemaRouteResponseDto response;
}
