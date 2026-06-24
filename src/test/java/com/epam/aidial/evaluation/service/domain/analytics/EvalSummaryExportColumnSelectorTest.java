package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EvalSummaryExportColumnSelector")
class EvalSummaryExportColumnSelectorTest {

    private EvalSummaryExportColumnSelector selector;

    @BeforeEach
    void setUp() {
        selector = new EvalSummaryExportColumnSelector();
    }

    @Test
    @DisplayName("Null input returns the planner output with body descriptors stripped, preserving order")
    void nullInputStripsBodyDescriptors() {
        List<ColumnDescriptor> manifest = manifestWithBodies();

        List<ColumnDescriptor> result = selector.select(manifest, null);

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .containsExactly("testCaseName", "data::prompt", "metric::Accuracy::score");
        assertThat(result).extracting(ColumnDescriptor::isBodyColumn).containsOnly(false);
    }

    @Test
    @DisplayName("Empty list input also strips body descriptors")
    void emptyInputStripsBodyDescriptors() {
        List<ColumnDescriptor> manifest = manifestWithBodies();

        List<ColumnDescriptor> result = selector.select(manifest, List.of());

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .containsExactly("testCaseName", "data::prompt", "metric::Accuracy::score")
                .doesNotContain("requestBody", "responseBody");
    }

    @Test
    @DisplayName("Non-empty input returns descriptors in the user-supplied order")
    void nonEmptyInputPreservesUserOrder() {
        List<ColumnDescriptor> manifest = manifestWithBodies();

        List<ColumnDescriptor> result =
                selector.select(manifest, List.of("metric::Accuracy::score", "testCaseName", "data::prompt"));

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .containsExactly("metric::Accuracy::score", "testCaseName", "data::prompt");
    }

    @Test
    @DisplayName("Subset that explicitly names responseBody returns it in the requested position")
    void subsetIncludingBodyKeepsItInOrder() {
        List<ColumnDescriptor> manifest = manifestWithBodies();

        List<ColumnDescriptor> result =
                selector.select(manifest, List.of("testCaseName", "responseBody", "metric::Accuracy::score"));

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .containsExactly("testCaseName", "responseBody", "metric::Accuracy::score");
        assertThat(result.get(1).isBodyColumn()).isTrue();
        assertThat(result.get(1).requiresJoinProjection()).isTrue();
    }

    @Test
    @DisplayName("Subset that names both requestBody and responseBody returns them both in order")
    void subsetIncludingBothBodiesReturnsThem() {
        List<ColumnDescriptor> manifest = manifestWithBodies();

        List<ColumnDescriptor> result = selector.select(manifest, List.of("requestBody", "responseBody"));

        assertThat(result).extracting(ColumnDescriptor::name).containsExactly("requestBody", "responseBody");
        assertThat(result).extracting(ColumnDescriptor::isBodyColumn).containsOnly(true);
    }

    @Test
    @DisplayName("Unknown column name surfaces ValidationException naming every offending column")
    void unknownColumnSurfacesValidationException() {
        List<ColumnDescriptor> manifest = manifestWithBodies();

        assertThatThrownBy(() -> selector.select(
                        manifest, List.of("testCaseName", "unknownOne", "metric::Accuracy::score", "unknownTwo")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unknownOne")
                .hasMessageContaining("unknownTwo");
    }

    @Test
    @DisplayName("Default subset preserves planner order for non-body descriptors")
    void defaultSubsetPreservesPlannerOrder() {
        List<ColumnDescriptor> manifest = new ArrayList<>();
        manifest.add(plain("a"));
        manifest.add(plain("b"));
        manifest.add(body("requestBody"));
        manifest.add(plain("c"));
        manifest.add(body("responseBody"));

        List<ColumnDescriptor> result = selector.select(manifest, null);

        assertThat(result).extracting(ColumnDescriptor::name).containsExactly("a", "b", "c");
    }

    private static List<ColumnDescriptor> manifestWithBodies() {
        return List.of(
                plain("testCaseName"),
                plain("data::prompt"),
                plain("metric::Accuracy::score"),
                body("requestBody"),
                body("responseBody"));
    }

    private static ColumnDescriptor plain(String name) {
        return new ColumnDescriptor(name, false, row -> null);
    }

    private static ColumnDescriptor body(String name) {
        return new ColumnDescriptor(name, true, row -> null);
    }
}
