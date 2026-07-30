package com.epam.aidial.evaluation.runner.dto;

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
    private Map<String, Object> arguments;
}
