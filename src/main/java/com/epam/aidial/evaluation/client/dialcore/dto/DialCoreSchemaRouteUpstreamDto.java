package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DialCoreSchemaRouteUpstreamDto {

    @JsonProperty("dial:endpoint")
    private String endpoint;

    @JsonProperty("dial:key")
    private String key;

    @JsonProperty("dial:extraData")
    private Object extraData;

    @JsonProperty("dial:weight")
    private Integer weight;

    @JsonProperty("dial:tier")
    private Integer tier;
}
