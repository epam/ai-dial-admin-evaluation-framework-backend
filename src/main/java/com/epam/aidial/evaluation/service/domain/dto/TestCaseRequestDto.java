package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class TestCaseRequestDto {

    @NotBlank(message = "Test case name is required")
    @Size(max = 255, message = "Test case name must be less than 255 characters")
    @Schema(example = "Smoke test - happy path")
    private String testCaseName;

    /** Omitted or explicit-null JSON yields an empty map (the no-args constructor is the Jackson creator). */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    @Schema(example = "{\"prompt\":\"Hello\",\"temperature\":0.7}")
    private Map<String, Object> data = Map.of();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Ordered array of per-turn data maps for a multi-turn test case. Mutually "
                    + "exclusive with 'data'. Omitted for single-turn cases.",
            example = "[{\"prompt\":\"Hi\"},{\"prompt\":\"And then?\"}]")
    private List<Map<String, Object>> multiTurnData;
}
