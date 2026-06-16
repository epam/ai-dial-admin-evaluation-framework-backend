package com.epam.aidial.evaluation.service.domain.dto.testcase.bulk;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Heterogeneous per-row merge-patch operation, identical in semantics to the single-row "
                + "PATCH /test-cases/{id} endpoint.")
public class TestCaseItemOperationDto {

    @NotNull
    @Schema(
            description = "Test-case UUID. MUST belong to the URL's test suite.",
            example = "11111111-1111-1111-1111-111111111111")
    private UUID id;

    @NotNull
    @Schema(
            description = "JSON merge-patch body (RFC 7396 semantics).",
            example = "{\"testCaseName\":\"Renamed\",\"data\":{\"prompt\":\"new prompt\"}}")
    private Map<String, Object> patch;
}
