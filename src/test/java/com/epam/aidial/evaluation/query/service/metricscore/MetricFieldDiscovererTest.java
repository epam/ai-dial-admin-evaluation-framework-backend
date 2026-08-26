package com.epam.aidial.evaluation.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MetricFieldDiscoverer")
class MetricFieldDiscovererTest {

    private static final String TWO_FIELD_SCHEMA =
            "{\"properties\":{\"score\":{\"type\":\"number\"},\"confidence\":{\"type\":\"number\"}}}";
    private static final String ONE_FIELD_SCHEMA = "{\"properties\":{\"score\":{\"type\":\"number\"}}}";

    private final MetricFieldDiscoverer discoverer =
            new MetricFieldDiscoverer(new OutputSchemaFieldExtractor(new ObjectMapper()));

    @Test
    @DisplayName("Should flatten each output field into a metric column and a stored metric name")
    void shouldFlattenEachOutputField() {
        List<MetricField> fields = discoverer.discover(List.of(snapshot("Relevancy", TWO_FIELD_SCHEMA)));

        assertThat(fields)
                .containsExactly(
                        new MetricField("metric::Relevancy::score", "Relevancy.score"),
                        new MetricField("metric::Relevancy::confidence", "Relevancy.confidence"));
    }

    @Test
    @DisplayName("Should keep a tsmd name containing a dot intact in both names")
    void shouldKeepDottedTsmdNameIntact() {
        List<MetricField> fields = discoverer.discover(List.of(snapshot("v1.2 Relevancy", ONE_FIELD_SCHEMA)));

        // The flattened name is unambiguous because it separates on "::", which the tsmd name cannot contain
        // in a way that collides here...
        assertThat(fields).extracting(MetricField::flattenedName).containsExactly("metric::v1.2 Relevancy::score");
        // ...but the stored metric_name joins on ".", so splitting it back on "." would yield "v1" — which is
        // exactly why callers must never recover the halves that way.
        assertThat(fields).extracting(MetricField::metricName).containsExactly("v1.2 Relevancy.score");
        assertThat(fields.get(0).metricName().split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("Should discover fields across snapshots in snapshot order")
    void shouldDiscoverAcrossSnapshotsInOrder() {
        List<MetricField> fields = discoverer.discover(
                List.of(snapshot("Relevancy", ONE_FIELD_SCHEMA), snapshot("Accuracy", ONE_FIELD_SCHEMA)));

        assertThat(fields).extracting(MetricField::metricName).containsExactly("Relevancy.score", "Accuracy.score");
    }

    @Test
    @DisplayName("Should return no fields for a snapshot whose schema declares none")
    void shouldReturnNoFieldsForSchemaWithoutProperties() {
        assertThat(discoverer.discover(List.of(snapshot("Relevancy", "{}")))).isEmpty();
        assertThat(discoverer.discover(List.of(snapshot("Relevancy", null)))).isEmpty();
        assertThat(discoverer.discover(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Should skip a snapshot with a malformed schema without failing the rest")
    void shouldSkipMalformedSchema() {
        List<MetricField> fields =
                discoverer.discover(List.of(snapshot("Broken", "{not json"), snapshot("Relevancy", ONE_FIELD_SCHEMA)));

        assertThat(fields).extracting(MetricField::metricName).containsExactly("Relevancy.score");
    }

    private static RunMetricSnapshot snapshot(String tsmdName, String outputSchema) {
        RunMetricSnapshot snapshot = new RunMetricSnapshot();
        snapshot.setTsmdName(tsmdName);
        snapshot.setOutputSchema(outputSchema);
        return snapshot;
    }
}
