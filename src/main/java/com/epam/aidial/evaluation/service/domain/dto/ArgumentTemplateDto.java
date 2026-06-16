package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArgumentTemplateDto {

    @NotNull
    @Schema(description = "Argument name to value/variable mapping", example = "{\"location\": \"${{city}}\"}")
    private Map<String, Object> arguments;
}
