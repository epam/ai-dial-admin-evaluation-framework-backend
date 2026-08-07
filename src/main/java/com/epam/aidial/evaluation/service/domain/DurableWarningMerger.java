package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Carries forward a test case's stored {@link ValidationWarningCode#SOURCE_CONFLICT} warnings across any
 * pass that <b>recomputes</b> the case's validity from stored state alone (the CSV-import fixup pass,
 * dataset revalidation Phase 1) — rather than from newly submitted user content.
 *
 * <p>A {@code SOURCE_CONFLICT} warning describes a conflict in the CSV rows a case was assembled from
 * (a duplicated {@code turnIndex}, or turn rows disagreeing on a shared column). The assembled case
 * itself is well-formed, so no later pass that only looks at stored data can re-derive the finding —
 * it can only be lost. This merger is the single place that stops that loss: called immediately before
 * a recomputation pass writes its result, it unions the recomputed warnings with whatever
 * {@code SOURCE_CONFLICT} entries are stored today, and keeps the case invalid while any remain.
 *
 * <p>Direct API writes ({@code PUT}/{@code PATCH} of a test case) are not recomputation — the caller
 * supplies new content — and MUST NOT call this merger; those paths clear the warnings as they do today.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class DurableWarningMerger {

    private final ValidationWarningsSerializer warningsSerializer;

    /**
     * Unions {@code recomputed}'s warnings with the {@code SOURCE_CONFLICT} entries found in
     * {@code storedWarningsJson}, without duplicating a warning the recomputation already produced.
     * The merged result is invalid whenever {@code recomputed} is invalid, or whenever any
     * {@code SOURCE_CONFLICT} warning was preserved from the stored warnings. A preserved conflict keeps
     * the case invalid regardless of what the recomputation concluded, because no pass that reads only
     * stored data can disprove a conflict in the rows the case was assembled from.
     *
     * @param recomputed the newly computed validation result, about to be written
     * @param storedWarningsJson the case's currently stored {@code validation_warnings} JSON (may be
     *     null, blank, or unreadable — all three are treated as "nothing to preserve")
     * @return a new {@link ValidationResult} carrying the union of warnings and the combined validity
     */
    public ValidationResult merge(ValidationResult recomputed, String storedWarningsJson) {
        List<ValidationWarningDto> stored = warningsSerializer.deserializeWarnings(storedWarningsJson);
        List<ValidationWarningDto> preserved = stored.stream()
                .filter(warning -> warning.getCode() == ValidationWarningCode.SOURCE_CONFLICT)
                .toList();

        List<ValidationWarningDto> recomputedWarnings =
                recomputed.getWarnings() != null ? recomputed.getWarnings() : List.of();
        List<ValidationWarningDto> merged = new ArrayList<>(recomputedWarnings);
        for (ValidationWarningDto warning : preserved) {
            if (!merged.contains(warning)) {
                merged.add(warning);
            }
        }

        boolean valid = recomputed.isValid() && preserved.isEmpty();
        return ValidationResult.builder().valid(valid).warnings(merged).build();
    }
}
