package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.stream.DoubleStream;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Direct SQL-level coverage of the {@code roc_auc_score} stored function (analytics DB) that backs the
 * Query DSL's {@code roc_auc} function, on real Postgres via Testcontainers.
 */
@DisplayName("roc_auc_score stored function Tests")
public abstract class RocAucScoreFunctionalTests extends BaseFunctionalTest {

    @Autowired
    @Qualifier("analyticsDsl")
    private DSLContext analyticsDsl;

    private Double rocAucScore(double[] y, double[] p) {
        return analyticsDsl
                .select(DSL.function("roc_auc_score", Double.class, DSL.array(boxed(y)), DSL.array(boxed(p))))
                .fetchOne(0, Double.class);
    }

    private static Double[] boxed(double[] values) {
        return DoubleStream.of(values).boxed().toArray(Double[]::new);
    }

    @Test
    @DisplayName("returns 1.0 for a perfect classifier (all positives ranked above all negatives)")
    void perfectClassifierReturnsOne() {
        Double auc = rocAucScore(new double[] {0, 0, 1, 1}, new double[] {0.1, 0.2, 0.8, 0.9});

        assertThat(auc).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("returns ~0.5 when probabilities carry no separating signal between classes")
    void noSeparationReturnsAroundOneHalf() {
        // Identical probability regardless of class: every pair ties -> average-rank AUC = 0.5.
        Double auc = rocAucScore(new double[] {0, 1, 0, 1}, new double[] {0.5, 0.5, 0.5, 0.5});

        assertThat(auc).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("averages ranks for tied probabilities spanning both classes")
    void tiedProbabilitiesUseAverageRank() {
        // Sorted by p: (0, 0.1) rank1, (0, 0.4) & (1, 0.4) tied rank(2,3)->avg 2.5, (1, 0.8) rank4.
        // rank_sum_pos = 2.5 + 4 = 6.5; n_pos=2, n_neg=2 -> auc = (6.5 - 3) / 4 = 0.875.
        Double auc = rocAucScore(new double[] {0, 0, 1, 1}, new double[] {0.1, 0.4, 0.4, 0.8});

        assertThat(auc).isCloseTo(0.875, within(1e-9));
    }

    @Test
    @DisplayName("returns null when only one class is present (no pair to rank)")
    void singleClassReturnsNull() {
        assertThat(rocAucScore(new double[] {1, 1, 1}, new double[] {0.2, 0.5, 0.9}))
                .isNull();
        assertThat(rocAucScore(new double[] {0, 0, 0}, new double[] {0.2, 0.5, 0.9}))
                .isNull();
    }
}
