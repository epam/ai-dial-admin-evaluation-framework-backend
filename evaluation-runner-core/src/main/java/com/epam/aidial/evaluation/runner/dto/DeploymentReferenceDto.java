package com.epam.aidial.evaluation.runner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeploymentReferenceDto {

    @NotBlank
    @Size(max = 255)
    private String id;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 50)
    private String version;

    @Size(max = 50)
    private String type;
}
