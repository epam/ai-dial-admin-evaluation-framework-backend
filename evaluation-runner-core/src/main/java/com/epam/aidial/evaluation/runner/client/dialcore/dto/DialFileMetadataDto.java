package com.epam.aidial.evaluation.runner.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DialFileMetadataDto {

    private String name;
    private String parentPath;
    private String bucket;
    private String url;
    private Long contentLength;
    private String contentType;
    private Long createdAt;
    private Long updatedAt;
}
