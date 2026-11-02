package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ScoreReferenceValidatorTest {

    private static final String ROC_AUC_TEMPLATE =
            """
            {"type":"custom_function","expression":{"entity":"eval_summaries","mode":"aggregate",
            "select":[{"expr":{"type":"fn","name":"roc_auc","args":[%s,%s]},"as":"overall"}]}}
            """;

    private ScoreReferenceValidator validator;

    @BeforeEach
    void setUp() {
        final ObjectMapper objectMapper = new ObjectMapper();
        validator = new ScoreReferenceValidator(
                new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper)), objectMapper);
    }

    private static String field(String name) {
        return "{\"type\":\"field\",\"name\":\"" + name + "\"}";
    }

    private static String weighted(String... names) {
        final StringBuilder rows = new StringBuilder();
        for (String name : names) {
            if (rows.length() > 0) {
                rows.append(',');
            }
            rows.append("{\"metricName\":")
                    .append(name == null ? "null" : "\"" + name + "\"")
                    .append(",\"outputField\":\"f\",\"weight\":1}");
        }
        return "{\"type\":\"weighted_mean\",\"weights\":[" + rows + "]}";
    }

    private List<ValidationWarningDto> run(String overall, String testCase, String... names) {
        return validator.validate(
                TestSuite.builder()
                        .overallScore(overall)
                        .testCaseOverallScore(testCase)
                        .build(),
                Set.of(names));
    }

    @Test
    @DisplayName("weighted_mean in both roots flags each unmatched name at its indexed path, case-sensitively")
    void weightedBothRoots() {
        final List<ValidationWarningDto> warnings = run(weighted("A", "Missing"), weighted("a", "A"), "A");

        assertThat(warnings)
                .extracting(ValidationWarningDto::getPath, ValidationWarningDto::getFieldName)
                .containsExactly(
                        tuple("$.overallScore.weights[1].metricName", "Missing"),
                        tuple("$.testCaseOverallScore.weights[0].metricName", "a"));
        assertThat(warnings).allMatch(w -> w.getCode() == ValidationWarningCode.UNRESOLVED_REFERENCE);
    }

    @Test
    @DisplayName("weighted_mean duplicates are each reported and null names are skipped")
    void weightedDuplicatesAndNull() {
        final List<ValidationWarningDto> warnings = run(weighted("X", null, "X"), null);

        assertThat(warnings)
                .extracting(ValidationWarningDto::getPath)
                .containsExactly(
                        "$.overallScore.weights[0].metricName", "$.overallScore.weights[2].metricName");
    }

    @Test
    @DisplayName("roc_auc flags only the unmatched metric:: argument; data:: and metricInfo:: are ignored")
    void rocAucMetricArg() {
        final String json = ROC_AUC_TEMPLATE.formatted(field("data::label"), field("metric::Classifier::probability"));
        final List<ValidationWarningDto> warnings = run(json, null);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFieldName()).isEqualTo("Classifier");
        assertThat(warnings.get(0).getPath()).isEqualTo("$.overallScore.expression.select[0].expr.args[1].name");
        assertThat(warnings.get(0).getCode()).isEqualTo(ValidationWarningCode.UNRESOLVED_REFERENCE);
        assertThat(run(json, null, "Classifier")).isEmpty();
        assertThat(run(
                        ROC_AUC_TEMPLATE.formatted(field("data::label"), field("metricInfo::Nope::x")), null))
                .isEmpty();
    }

    @Test
    @DisplayName("metric:: argument without output field resolves by the whole remainder")
    void metricWithoutOutput() {
        final String json = ROC_AUC_TEMPLATE.formatted(field("data::label"), field("metric::Classifier"));

        assertThat(run(json, null)).extracting(ValidationWarningDto::getFieldName).containsExactly("Classifier");
        assertThat(run(json, null, "Classifier")).isEmpty();
    }

    @Test
    @DisplayName("TSMD name containing :: resolves up to the last separator")
    void nameWithSeparator() {
        final String json = ROC_AUC_TEMPLATE.formatted(field("metric::A::b::out"), field("data::label"));

        assertThat(run(json, null, "A::b")).isEmpty();
        assertThat(run(json, null, "A")).extracting(ValidationWarningDto::getFieldName).containsExactly("A::b");
    }

    @Test
    @DisplayName("other function, two selects and non-convertible expression give one UNSUPPORTED_SCORE_FUNCTION")
    void unsupportedShapes() {
        final String avg =
                """
                {"type":"custom_function","expression":{"entity":"eval_summaries","mode":"aggregate",
                "select":[{"expr":{"type":"fn","name":"avg","args":[{"type":"field","name":"metric::R::s"}]},"as":"o"}]}}
                """;
        final String two =
                """
                {"type":"custom_function","expression":{"entity":"eval_summaries","mode":"aggregate",
                "select":[{"expr":{"type":"field","name":"a"}},{"expr":{"type":"field","name":"b"}}]}}
                """;
        final String garbage = "{\"type\":\"custom_function\",\"expression\":{\"select\":\"nope\"}}";

        for (String json : List.of(avg, two, garbage)) {
            final List<ValidationWarningDto> warnings = run(json, null);
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getCode()).isEqualTo(ValidationWarningCode.UNSUPPORTED_SCORE_FUNCTION);
            assertThat(warnings.get(0).getPath()).isEqualTo("$.overallScore.expression");
        }
    }

    @Test
    @DisplayName("unreadable overallScore column yields UNSUPPORTED_SCORE_FUNCTION; unreadable testCaseOverallScore is ignored")
    void unreadableColumns() {
        final List<ValidationWarningDto> warnings = run("{not json", "{not json");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getCode()).isEqualTo(ValidationWarningCode.UNSUPPORTED_SCORE_FUNCTION);
    }

    @Test
    @DisplayName("legacy custom_function in testCaseOverallScore, mean and null produce no warnings")
    void ignoredShapes() {
        final String legacy = ROC_AUC_TEMPLATE.formatted(field("metric::Z::x"), field("metric::Y::x"));

        assertThat(run("{\"type\":\"mean\"}", legacy)).isEmpty();
        assertThat(run(null, null)).isEmpty();
    }
}
