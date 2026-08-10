package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CsvRunGrouper")
class CsvTestCaseGrouperTest {

    private final CsvTestCaseGrouper grouper = new CsvTestCaseGrouper();

    private static ParsedCsvRow row(int rowNumber, String name, Integer turnIndex) {
        return new ParsedCsvRow(rowNumber, name, turnIndex, Map.of(), false);
    }

    @Nested
    @DisplayName("run boundaries")
    class RunBoundaries {

        @Test
        @DisplayName("a name change closes the current run and starts a new one")
        void nameChangeClosesRun() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();

            assertThat(acc.add(row(2, "a", null))).isNull();
            CsvTestCase completed = acc.add(row(3, "b", null));

            assertThat(completed).isNotNull();
            assertThat(completed.testCaseName()).isEqualTo("a");
            assertThat(completed.rows()).hasSize(1);
            assertThat(completed.firstRowNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("single-turn passthrough: no row carries a turnIndex, run is not multi-turn")
        void singleTurnPassthrough() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "a", null));

            CsvTestCase completed = acc.add(row(3, "b", null));

            assertThat(completed.multiTurn()).isFalse();
            assertThat(completed.nonContiguous()).isFalse();
        }

        @Test
        @DisplayName("run boundaries compare names case-sensitively — differently-cased names split into two runs")
        void caseSensitiveBoundary() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "DupRow", null));

            CsvTestCase completed = acc.add(row(3, "duprow", null));

            assertThat(completed).isNotNull();
            assertThat(completed.testCaseName()).isEqualTo("DupRow");
        }

        @Test
        @DisplayName("flush() returns the trailing run")
        void flushReturnsTrailingRun() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "a", null));
            acc.add(row(3, "a", null));

            CsvTestCase flushed = acc.flush();

            assertThat(flushed).isNotNull();
            assertThat(flushed.rows()).hasSize(2);
            assertThat(flushed.testCaseName()).isEqualTo("a");
        }

        @Test
        @DisplayName("flush() returns null when nothing was added since the last flush")
        void flushReturnsNullWhenEmpty() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();

            assertThat(acc.flush()).isNull();
        }
    }

    @Nested
    @DisplayName("multi-turn detection")
    class MultiTurnDetection {

        @Test
        @DisplayName("a row whose turnIndex parses to a non-null Integer makes the run multi-turn")
        void nonNullTurnIndexMakesRunMultiTurn() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "conv", 0));
            acc.add(row(3, "conv", 1));

            CsvTestCase completed = acc.flush();

            assertThat(completed.multiTurn()).isTrue();
        }

        @Test
        @DisplayName("an unparseable turnIndex cell (already null from parsing) keeps the run single-turn")
        void unparseableTurnIndexStaysSingleTurn() {
            // parseTurnIndex already turns an unparseable cell like "abc" into null before the row reaches
            // the grouper, so a row with turnIndex()==null is indistinguishable from a blank cell here —
            // exactly the behavior being pinned.
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "conv", null));

            CsvTestCase completed = acc.flush();

            assertThat(completed.multiTurn()).isFalse();
        }
    }

    @Nested
    @DisplayName("non-contiguity tracking")
    class NonContiguityTracking {

        @Test
        @DisplayName("a multi-turn name reappearing after another run is flagged non-contiguous")
        void multiTurnNameReappearingIsFlagged() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "conv", 0));
            CsvTestCase firstConvRun = acc.add(row(3, "other", null));
            CsvTestCase otherRun = acc.add(row(4, "conv", 1));
            CsvTestCase secondConvRun = acc.flush();

            assertThat(firstConvRun.multiTurn()).isTrue();
            assertThat(firstConvRun.nonContiguous()).isFalse();
            assertThat(otherRun.multiTurn()).isFalse();
            assertThat(secondConvRun.testCaseName()).isEqualTo("conv");
            assertThat(secondConvRun.nonContiguous()).isTrue();
        }

        @Test
        @DisplayName("non-contiguity is never flagged for a repeated blank-turnIndex (single-turn) name")
        void neverFlaggedForSingleTurnRepeats() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "dup", null));
            CsvTestCase firstDupRun = acc.add(row(3, "other", null));
            CsvTestCase otherRun = acc.add(row(4, "dup", null));
            CsvTestCase secondDupRun = acc.flush();

            assertThat(firstDupRun.nonContiguous()).isFalse();
            assertThat(otherRun.nonContiguous()).isFalse();
            assertThat(secondDupRun.nonContiguous()).isFalse();
        }

        @Test
        @DisplayName("non-contiguity tracking is case-insensitive on the multi-turn name")
        void nonContiguityTrackingIsCaseInsensitive() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "Conv", 0));
            acc.add(row(3, "other", 0));
            acc.add(row(4, "CONV", 1));
            CsvTestCase secondRun = acc.flush();

            assertThat(secondRun.nonContiguous()).isTrue();
        }
    }

    @Nested
    @DisplayName("run contents")
    class RunContents {

        @Test
        @DisplayName("a multi-turn run carries all of its rows in CSV order")
        void multiTurnRunCarriesAllRows() {
            CsvTestCaseGrouper.Accumulator acc = grouper.newAccumulator();
            acc.add(row(2, "conv", 0));
            acc.add(row(3, "conv", 1));
            CsvTestCase completed = acc.flush();

            assertThat(completed.rows()).extracting(ParsedCsvRow::rowNumber).containsExactly(2, 3);
        }

        @Test
        @DisplayName("consecutive accumulator instances from newAccumulator() do not share state")
        void accumulatorsAreIndependent() {
            CsvTestCaseGrouper.Accumulator first = grouper.newAccumulator();
            first.add(row(2, "conv", 0));
            first.flush();

            CsvTestCaseGrouper.Accumulator second = grouper.newAccumulator();
            second.add(row(2, "conv", 0));
            CsvTestCase secondFlush = second.flush();

            // If state leaked across accumulators, "conv" would be seen as already-completed and flagged.
            assertThat(secondFlush.nonContiguous()).isFalse();
        }
    }

    @Test
    @DisplayName("newAccumulator() is a stateless factory: the grouper bean itself holds no per-request state")
    void newAccumulatorIsStatelessFactory() {
        CsvTestCaseGrouper.Accumulator a1 = grouper.newAccumulator();
        CsvTestCaseGrouper.Accumulator a2 = grouper.newAccumulator();

        assertThat(a1).isNotSameAs(a2);
    }
}
