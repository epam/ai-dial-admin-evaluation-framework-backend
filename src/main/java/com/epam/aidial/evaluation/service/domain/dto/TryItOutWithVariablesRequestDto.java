package com.epam.aidial.evaluation.service.domain.dto;

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
public class TryItOutWithVariablesRequestDto {

    @NotNull(message = "Variables map is required (use empty map for fully static templates)")
    private Map<String, Object> variables;
}
