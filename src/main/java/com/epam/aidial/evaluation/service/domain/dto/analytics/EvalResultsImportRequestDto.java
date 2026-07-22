package com.epam.aidial.evaluation.service.domain.dto.analytics;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for importing already-produced eval results (raw model responses) into an
 * existing test suite and running metric evaluation + score computation on them, skipping
 * deployment invocation entirely. The target suite is identified by the {@code testSuiteId}
 * path variable on the endpoint; this DTO only carries the run name and the result batch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalResultsImportRequestDto {

    @Size(max = 255, message = "testRunName must be less than 255 characters")
    private String testRunName;

    @NotEmpty(message = "results must not be empty")
    @Valid
    private List<EvalResultsImportItemDto> results;
}
