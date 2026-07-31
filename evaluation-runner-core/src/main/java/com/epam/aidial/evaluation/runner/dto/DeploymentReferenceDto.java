package com.epam.aidial.evaluation.runner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(example = "deploy-001")
    private String id;

    @NotBlank
    @Size(max = 255)
    @Schema(example = "Production Deployment")
    private String name;

    @Size(max = 50)
    @Schema(example = "1.0")
    private String version;

    @Size(max = 50)
    @Schema(example = "dial-application", description = "Deployment type: dial-model or dial-application")
    private String type;
}
