package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("EvalSummaryExportRow.metricInfo / metricWholesaleError routing")
class EvalSummaryExportRowTest {

    private static final String METRIC = "Retrieval";
    private static final Set<String> SCHEMA_FIELDS = Set.of("recall", "precision", "f1", "mrr");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EvalSummaryExportRow rowWithMetricInfos(String metricInfosJson) {
        EvalSummary summary = EvalSummary.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testSuiteRunId(UUID.randomUUID())
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("case")
                .runIndex(0)
                .computationId(UUID.randomUUID())
                .metricInfos(metricInfosJson)
                .build();
        return new EvalSummaryExportRow(summary, objectMapper);
    }

    @BeforeEach
    void noop() {
        // Each test builds its own row.
    }

    @Test
    @DisplayName("Per-field success returns the field's details payload and leaves metricError empty")
    void perFieldSuccessReturnsDetails() {
        String json = "{\"Retrieval\":{"
                + "\"recall\":{\"details\":{\"facts_ranks\":[0,2]}},"
                + "\"precision\":{\"details\":{}},"
                + "\"f1\":{\"details\":{}},"
                + "\"mrr\":{\"details\":{}}}}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        JsonNode recall = row.metricInfo(METRIC, "recall", SCHEMA_FIELDS);
        JsonNode wholesale = row.metricWholesaleError(METRIC, SCHEMA_FIELDS);

        assertThat(recall).isNotNull();
        assertThat(recall.get("details").get("facts_ranks").toString()).isEqualTo("[0,2]");
        assertThat(wholesale).isNull();
    }

    @Test
    @DisplayName(
            "Per-field error envelope routes to the field's metricInfo cell, metricError stays empty when other fields cover schema")
    void perFieldErrorRoutesToFieldCell() {
        String json = "{\"Retrieval\":{"
                + "\"recall\":{\"type\":\"error\",\"message\":\"facts missing\"},"
                + "\"precision\":{\"details\":{}},"
                + "\"f1\":{\"details\":{}},"
                + "\"mrr\":{\"details\":{}}}}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        JsonNode recall = row.metricInfo(METRIC, "recall", SCHEMA_FIELDS);
        JsonNode precision = row.metricInfo(METRIC, "precision", SCHEMA_FIELDS);
        JsonNode wholesale = row.metricWholesaleError(METRIC, SCHEMA_FIELDS);

        assertThat(recall.get("type").asString()).isEqualTo("error");
        assertThat(recall.get("message").asString()).isEqualTo("facts missing");
        assertThat(precision.get("details").isObject()).isTrue();
        assertThat(wholesale).isNull();
    }

    @Test
    @DisplayName("Partial per-field map keeps absent-field cells null but still counts as a per-field map")
    void partialPerFieldMapIsStillPerFieldMap() {
        String json = "{\"Retrieval\":{\"recall\":{\"details\":{}}}}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        assertThat(row.metricInfo(METRIC, "recall", SCHEMA_FIELDS).isObject()).isTrue();
        assertThat(row.metricInfo(METRIC, "precision", SCHEMA_FIELDS)).isNull();
        assertThat(row.metricInfo(METRIC, "f1", SCHEMA_FIELDS)).isNull();
        assertThat(row.metricInfo(METRIC, "mrr", SCHEMA_FIELDS)).isNull();
        assertThat(row.metricWholesaleError(METRIC, SCHEMA_FIELDS)).isNull();
    }

    @Test
    @DisplayName("Wholesale-error envelope (no schema-key overlap) routes the whole payload to metricError")
    void wholesaleErrorRoutesToMetricError() {
        String json = "{\"Retrieval\":{\"type\":\"error\",\"message\":\"metric crashed before evaluation\"}}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        JsonNode wholesale = row.metricWholesaleError(METRIC, SCHEMA_FIELDS);

        assertThat(wholesale).isNotNull();
        assertThat(wholesale.get("type").asString()).isEqualTo("error");
        assertThat(wholesale.get("message").asString()).isEqualTo("metric crashed before evaluation");
        for (String field : SCHEMA_FIELDS) {
            assertThat(row.metricInfo(METRIC, field, SCHEMA_FIELDS))
                    .as("per-field cell for %s", field)
                    .isNull();
        }
    }

    @Test
    @DisplayName("Non-object metricInfos entry (string) routes to metricError")
    void nonObjectStringEntryRoutesToMetricError() {
        String json = "{\"Retrieval\":\"unrecoverable failure\"}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        JsonNode wholesale = row.metricWholesaleError(METRIC, SCHEMA_FIELDS);

        assertThat(wholesale).isNotNull();
        assertThat(wholesale.isString()).isTrue();
        assertThat(wholesale.asString()).isEqualTo("unrecoverable failure");
        assertThat(row.metricInfo(METRIC, "recall", SCHEMA_FIELDS)).isNull();
    }

    @Test
    @DisplayName("Non-object metricInfos entry (array) routes to metricError")
    void nonObjectArrayEntryRoutesToMetricError() {
        String json = "{\"Retrieval\":[1,2,3]}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        JsonNode wholesale = row.metricWholesaleError(METRIC, SCHEMA_FIELDS);

        assertThat(wholesale).isNotNull();
        assertThat(wholesale.isArray()).isTrue();
        assertThat(wholesale.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("Missing per-metric entry leaves both per-field and metricError cells null")
    void missingPerMetricEntry() {
        String json = "{\"OtherMetric\":{\"recall\":{}}}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        for (String field : SCHEMA_FIELDS) {
            assertThat(row.metricInfo(METRIC, field, SCHEMA_FIELDS))
                    .as("per-field cell for %s", field)
                    .isNull();
        }
        assertThat(row.metricWholesaleError(METRIC, SCHEMA_FIELDS)).isNull();
    }

    @Test
    @DisplayName("Empty metricInfos object yields null on every routing path")
    void emptyMetricInfosObject() {
        EvalSummaryExportRow row = rowWithMetricInfos("{}");

        assertThat(row.metricInfo(METRIC, "recall", SCHEMA_FIELDS)).isNull();
        assertThat(row.metricWholesaleError(METRIC, SCHEMA_FIELDS)).isNull();
    }

    @Test
    @DisplayName("Null/blank metricInfos input yields null on every routing path")
    void nullMetricInfosInput() {
        EvalSummaryExportRow nullRow = rowWithMetricInfos(null);
        EvalSummaryExportRow blankRow = rowWithMetricInfos("   ");

        assertThat(nullRow.metricInfo(METRIC, "recall", SCHEMA_FIELDS)).isNull();
        assertThat(nullRow.metricWholesaleError(METRIC, SCHEMA_FIELDS)).isNull();
        assertThat(blankRow.metricInfo(METRIC, "recall", SCHEMA_FIELDS)).isNull();
        assertThat(blankRow.metricWholesaleError(METRIC, SCHEMA_FIELDS)).isNull();
    }

    @Test
    @DisplayName("Explicit JSON null at the per-metric key is treated as missing")
    void explicitJsonNullAtPerMetricKey() {
        String json = "{\"Retrieval\":null}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        assertThat(row.metricInfo(METRIC, "recall", SCHEMA_FIELDS)).isNull();
        assertThat(row.metricWholesaleError(METRIC, SCHEMA_FIELDS)).isNull();
    }

    @Test
    @DisplayName("Empty schema-field set never classifies a payload as per-field map — everything is wholesale-error")
    void emptySchemaFieldSetForcesWholesaleRouting() {
        String json = "{\"Retrieval\":{\"recall\":{}}}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        assertThat(row.metricInfo(METRIC, "recall", Set.of())).isNull();
        assertThat(row.metricWholesaleError(METRIC, Set.of())).isNotNull();
    }

    @Test
    @DisplayName("Explicit JSON null at the per-field key inside a per-field map renders as null cell")
    void explicitNullAtFieldKey() {
        String json = "{\"Retrieval\":{\"recall\":null,\"precision\":{\"details\":{}}}}";
        EvalSummaryExportRow row = rowWithMetricInfos(json);

        assertThat(row.metricInfo(METRIC, "recall", SCHEMA_FIELDS)).isNull();
        assertThat(row.metricInfo(METRIC, "precision", SCHEMA_FIELDS)
                        .get("details")
                        .isObject())
                .isTrue();
        assertThat(row.metricWholesaleError(METRIC, SCHEMA_FIELDS)).isNull();
    }
}
