package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
@Schema(description = "Batch PUT item for updating an existing test case")
public class TestCaseBatchPutItemDto {

    @NotNull(message = "Test case id is required")
    @Schema(example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID id;

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
