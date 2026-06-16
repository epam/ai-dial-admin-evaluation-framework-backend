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
public class DialCoreSchemaRouteResponseDto {

    @JsonProperty("dial:status")
    private Integer status;

    @JsonProperty("dial:body")
    private String body;
}
