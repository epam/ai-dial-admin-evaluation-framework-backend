package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.TestCaseFieldScopeResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Assembles a multi-turn {@link CsvTestCase} into its shared/per-turn shape and detects the conflicts that
 * describe the submitted rows rather than the resulting case: a shared-column mismatch across the run's
 * rows, and a duplicate {@code turnIndex} within the run. A pure function — no repository or validation
 * service dependency — so it is usable by both CSV preview and import without either persisting or
 * pre-supposing a validation call. Scope partitioning (shared vs per-turn) is delegated to
 * {@link TestCaseFieldScopeResolver}, the single source of truth for that split.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class MultiTurnRunAssembler {

    private final TestCaseFieldScopeResolver scopeResolver;

    /**
     * Orders {@code run}'s rows by their {@code turnIndex} ordering hint (nulls last, stable by CSV row
     * order), splits each row's data into shared/per-turn maps by {@code schema}'s scope, and reports a
     * shared-column mismatch and/or a duplicate {@code turnIndex} as data rather than throwing or logging.
     */
    public MultiTurnAssembly assemble(CsvTestCase run, List<FieldDefinitionDto> schema) {
        List<ParsedCsvRow> ordered = orderTurns(run.rows());

        Map<String, Object> shared = null;
        boolean sharedConflict = false;
        boolean hasJsonParseErrors = false;
        List<Map<String, Object>> perTurnMaps = new ArrayList<>(ordered.size());
        for (ParsedCsvRow row : ordered) {
            TestCaseFieldScopeResolver.Partition partition = scopeResolver.partition(row.data(), schema);
            if (shared == null) {
                shared = partition.shared();
            } else if (!shared.equals(partition.shared())) {
                sharedConflict = true;
            }
            perTurnMaps.add(partition.perTurn());
            hasJsonParseErrors |= row.hasJsonParseErrors();
        }

        return new MultiTurnAssembly(
                shared != null ? shared : Map.of(),
                perTurnMaps,
                sharedConflict,
                hasDuplicateTurnIndex(run.rows()),
                hasJsonParseErrors);
    }

    /** Orders a multi-turn run by its turnIndex ordering hint (nulls last, stable by CSV row order). */
    private List<ParsedCsvRow> orderTurns(List<ParsedCsvRow> run) {
        List<ParsedCsvRow> ordered = new ArrayList<>(run);
        ordered.sort(
                Comparator.comparingInt((ParsedCsvRow r) -> r.turnIndex() == null ? Integer.MAX_VALUE : r.turnIndex())
                        .thenComparingInt(ParsedCsvRow::rowNumber));
        return ordered;
    }

    private boolean hasDuplicateTurnIndex(List<ParsedCsvRow> run) {
        Set<Integer> seen = new HashSet<>();
        for (ParsedCsvRow r : run) {
            if (r.turnIndex() != null && !seen.add(r.turnIndex())) {
                return true;
            }
        }
        return false;
    }
}
