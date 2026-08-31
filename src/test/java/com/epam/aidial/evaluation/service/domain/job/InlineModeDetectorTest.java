package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.UrlEncodedFormRequestBodyDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("InlineModeDetector")
class InlineModeDetectorTest {

    private final InlineModeDetector detector = new InlineModeDetector(new ObjectMapper());

    private SuiteSnapshotDto snapshot(RequestTemplateDto requestTemplate) {
        return SuiteSnapshotDto.builder()
                .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                .suiteType("DEPLOYMENT")
                .requestTemplate(requestTemplate)
                .build();
    }

    private RequestTemplateDto jsonTemplate(Map<String, Object> content, String jsonataContent) {
        return RequestTemplateDto.builder()
                .body(JsonRequestBodyDto.builder()
                        .content(content)
                        .jsonataContent(jsonataContent)
                        .build())
                .build();
    }

    private AggregatedMetricDefinition tsmd(String configBindings, String inputBindings) {
        return AggregatedMetricDefinition.builder()
                .name("Accuracy")
                .configBindings(configBindings)
                .inputBindings(inputBindings)
                .build();
    }

    @Test
    @DisplayName("Root request body content reference makes the run inline")
    void rootBodyContentReference_isInline() {
        SuiteSnapshotDto snapshot = snapshot(jsonTemplate(Map.of("value", "$_metrics.judge.score.value"), null));
        assertThat(detector.isInline(snapshot, List.of())).isTrue();
    }

    @Test
    @DisplayName("Additional-request body reference makes the run inline")
    void additionalRequestBodyReference_isInline() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                .suiteType("DEPLOYMENT")
                .requestTemplate(jsonTemplate(Map.of("op", "configure"), null))
                .additionalRequests(List.of(RequestDefinitionDto.builder()
                        .requestTemplate(jsonTemplate(null, "{\"v\": $_metrics.judge.score.value}"))
                        .build()))
                .build();
        assertThat(detector.isInline(snapshot, List.of())).isTrue();
    }

    @Test
    @DisplayName("jsonataContent reference makes the run inline")
    void jsonataContentReference_isInline() {
        SuiteSnapshotDto snapshot = snapshot(jsonTemplate(null, "{\"v\": $_metrics.judge.score.value}"));
        assertThat(detector.isInline(snapshot, List.of())).isTrue();
    }

    @Test
    @DisplayName("An enabled+valid TSMD's configBindings/inputBindings reference makes the run inline")
    void tsmdBindingReference_isInline() {
        SuiteSnapshotDto snapshot = snapshot(jsonTemplate(Map.of("op", "configure"), null));
        AggregatedMetricDefinition tsmd = tsmd(
                "[]",
                "[{\"property\":\"actual\",\"source\":{\"$type\":\"Expression\","
                        + "\"expression\":\"$_metrics.judge.score.value\"}}]");
        assertThat(detector.isInline(snapshot, List.of(tsmd))).isTrue();
    }

    @Test
    @DisplayName(
            "A multipart or URL-encoded body referencing $_metrics is not scanned and does not make the run inline")
    void nonJsonBody_notScanned() {
        RequestTemplateDto urlEncodedTemplate = RequestTemplateDto.builder()
                .body(UrlEncodedFormRequestBodyDto.builder()
                        .content(List.of(KeyValueTemplateDto.builder()
                                .key("v")
                                .value("$_metrics.judge.score.value")
                                .build()))
                        .build())
                .build();
        SuiteSnapshotDto snapshot = snapshot(urlEncodedTemplate);
        assertThat(detector.isInline(snapshot, List.of())).isFalse();
    }

    @Test
    @DisplayName("A URL template or header referencing $_metrics is not scanned and does not make the run inline")
    void urlOrHeaderReference_notScanned() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/ask?metrics=$_metrics.judge.score.value")
                .headers(List.of(KeyValueTemplateDto.builder()
                        .key("X-Metrics")
                        .value("$_metrics.judge.score.value")
                        .build()))
                .body(JsonRequestBodyDto.builder().content(Map.of("op", "ask")).build())
                .build();
        SuiteSnapshotDto snapshot = snapshot(template);
        assertThat(detector.isInline(snapshot, List.of())).isFalse();
    }

    @Test
    @DisplayName("MCP_TOOL suite is always non-inline, regardless of $_metrics text present anywhere")
    void mcpSuite_alwaysNonInline() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                .suiteType("MCP_TOOL")
                .requestTemplate(jsonTemplate(Map.of("value", "$_metrics.judge.score.value"), null))
                .build();
        AggregatedMetricDefinition tsmd = tsmd("$_metrics.judge.score.value", "[]");
        assertThat(detector.isInline(snapshot, List.of(tsmd))).isFalse();
    }

    @Test
    @DisplayName("Suite without any $_metrics reference stays non-inline")
    void noMatch_staysNonInline() {
        SuiteSnapshotDto snapshot = snapshot(jsonTemplate(Map.of("op", "configure"), null));
        AggregatedMetricDefinition tsmd =
                tsmd("[]", "[{\"property\":\"actual\",\"source\":{\"$type\":\"Response\",\"columnName\":\"answer\"}}]");
        assertThat(detector.isInline(snapshot, List.of(tsmd))).isFalse();
    }

    @Test
    @DisplayName("A null request template does not throw and is treated as a non-hit")
    void nullRequestTemplate_isNotHit() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                .suiteType("DEPLOYMENT")
                .build();
        assertThat(detector.isInline(snapshot, List.of())).isFalse();
    }
}
