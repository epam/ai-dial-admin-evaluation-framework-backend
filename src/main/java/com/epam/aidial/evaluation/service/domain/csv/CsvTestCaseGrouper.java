package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Groups a stream of {@link ParsedCsvRow}s into contiguous {@link CsvTestCase}s: a maximal group of consecutive
 * rows sharing a {@code testCaseName}, compared case-sensitively (exactly as import does today — this
 * asymmetry against case-insensitive duplicate detection is intentional and load-bearing). A row whose
 * {@code turnIndex} parses to a non-null Integer makes its run multi-turn.
 *
 * <p>This component is stateless: grouping a CSV is inherently stateful (the run under construction, and
 * which multi-turn names have already completed a run), so per-request state lives in a small accumulator
 * this component hands out via {@link #newAccumulator()} rather than on the bean itself. Shared by CSV
 * preview and import so both group rows the same way.
 */
@Component
@LogExecution
public class CsvTestCaseGrouper {

    /** Starts a new, independent grouping session — one per CSV parse. */
    public Accumulator newAccumulator() {
        return new Accumulator();
    }

    /**
     * Per-call, single-use accumulator: feed rows one at a time via {@link #add(ParsedCsvRow)}, which
     * returns the just-closed run (or {@code null} while the current run is still open), then call
     * {@link #flush()} once the CSV is exhausted to retrieve the trailing run. Not thread-safe; not
     * reusable across CSV parses.
     */
    public static final class Accumulator {

        private final List<ParsedCsvRow> currentRun = new ArrayList<>();
        private final Set<String> completedMultiTurnNames = new HashSet<>();

        private Accumulator() {}

        /**
         * Adds one row to the run under construction. Returns the completed run when this row's
         * {@code testCaseName} differs from the current run's name; otherwise returns {@code null}.
         */
        public CsvTestCase add(ParsedCsvRow row) {
            CsvTestCase completed = null;
            if (!currentRun.isEmpty() && !currentRun.getFirst().testCaseName().equals(row.testCaseName())) {
                completed = closeRun();
            }
            currentRun.add(row);
            return completed;
        }

        /** Closes and returns the trailing run, or {@code null} if no row was ever added since the last flush. */
        public CsvTestCase flush() {
            return currentRun.isEmpty() ? null : closeRun();
        }

        private CsvTestCase closeRun() {
            List<ParsedCsvRow> rows = List.copyOf(currentRun);
            currentRun.clear();
            boolean multiTurn = rows.stream().anyMatch(r -> r.turnIndex() != null);
            String testCaseName = rows.getFirst().testCaseName();
            // Non-contiguity is tracked for multi-turn runs only: a single-turn run's repeated name is an
            // ordinary duplicate, handled per-row by the conflict strategy, not a non-contiguity conflict.
            boolean nonContiguous = multiTurn && !completedMultiTurnNames.add(testCaseName.toLowerCase());
            return new CsvTestCase(rows, testCaseName, rows.getFirst().rowNumber(), multiTurn, nonContiguous);
        }
    }
}
