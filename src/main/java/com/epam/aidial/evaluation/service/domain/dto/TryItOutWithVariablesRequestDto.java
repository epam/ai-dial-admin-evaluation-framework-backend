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
public class TryItOutWithVariablesRequestDto {

    @NotNull(message = "Variables map is required (use empty map for fully static templates)")
    @Schema(
            description = "Template variable values keyed by request index: \"0\" is the suite's own request, \"n\" is"
                    + " additionalRequests[n-1]. Each request resolves only with its own entry; a missing index means"
                    + " no variables for that request. Single-request and MCP suites accept only \"0\".",
            example = "{\"0\": {\"user_message\": \"first\"}, \"1\": {\"user_message\": \"second\"}}")
    private Map<Integer, Map<String, Object>> variables;
}
