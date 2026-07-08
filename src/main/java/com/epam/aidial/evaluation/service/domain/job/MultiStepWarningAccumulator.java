package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.service.domain.dto.analytics.ExtractionWarningDto;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-conversation accumulator for multi-step extraction warnings. Each completed step contributes the
 * warnings produced by {@code ResponseColumnExtractor} for that turn (the same warnings the single-step
 * path records); every warning is stamped with its 0-based {@code stepIndex} so the persisted array
 * pinpoints which turn each problem came from. {@link #toJson()} serializes the flat, ordered list
 * ({@code []} when no steps produced warnings), reusing {@link ValidationWarningsSerializer}.
 *
 * <p>Not a Spring bean — it holds mutable per-conversation state and is instantiated once per {@code
 * execute()}, mirroring its sibling {@link MultiStepColumnAccumulator}.
 */
public class MultiStepWarningAccumulator {

    private final ValidationWarningsSerializer warningsSerializer;
    private final List<ExtractionWarningDto> warnings = new ArrayList<>();

    public MultiStepWarningAccumulator(ValidationWarningsSerializer warningsSerializer) {
        this.warningsSerializer = warningsSerializer;
    }

    public void addStep(int stepIndex, String stepExtractionWarningsJson) {
        for (ExtractionWarningDto warning :
                warningsSerializer.deserializeExtractionWarnings(stepExtractionWarningsJson)) {
            warning.setStepIndex(stepIndex);
            warnings.add(warning);
        }
    }

    public String toJson() {
        return warningsSerializer.serializeExtractionWarnings(warnings);
    }
}
