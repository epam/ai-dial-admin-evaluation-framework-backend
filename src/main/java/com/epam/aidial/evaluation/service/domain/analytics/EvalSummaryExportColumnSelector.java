package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective column subset for an EvalSummary export.
 *
 * <p>This is the single place that distinguishes "default" from "explicit subset":
 * <ul>
 *   <li><b>Empty / null input</b>: returns the full manifest with body descriptors
 *       ({@code isBodyColumn = true}) stripped — the default-set rule from design D4.</li>
 *   <li><b>Non-empty input</b>: returns the user-ordered subset; throws
 *       {@link ValidationException} listing every unknown name.</li>
 * </ul>
 * The planner and the downstream projection-picker stay branch-free.
 */
@Slf4j
@Component
@LogExecution
public class EvalSummaryExportColumnSelector {

    public List<ColumnDescriptor> select(List<ColumnDescriptor> fullManifest, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return defaultSubset(fullManifest);
        }
        return explicitSubset(fullManifest, requested);
    }

    private static List<ColumnDescriptor> defaultSubset(List<ColumnDescriptor> fullManifest) {
        List<ColumnDescriptor> result = new ArrayList<>(fullManifest.size());
        for (ColumnDescriptor descriptor : fullManifest) {
            if (!descriptor.isBodyColumn()) {
                result.add(descriptor);
            }
        }
        return result;
    }

    private static List<ColumnDescriptor> explicitSubset(List<ColumnDescriptor> fullManifest, List<String> requested) {
        Map<String, ColumnDescriptor> byName = new LinkedHashMap<>(fullManifest.size());
        for (ColumnDescriptor descriptor : fullManifest) {
            byName.put(descriptor.name(), descriptor);
        }
        List<ColumnDescriptor> result = new ArrayList<>(requested.size());
        List<String> unknown = new ArrayList<>();
        for (String name : requested) {
            ColumnDescriptor descriptor = byName.get(name);
            if (descriptor == null) {
                unknown.add(name);
            } else {
                result.add(descriptor);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ValidationException("Unknown export column(s): " + String.join(", ", unknown));
        }
        return result;
    }
}
