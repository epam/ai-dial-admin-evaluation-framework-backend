package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.analytics.ExtractionWarningDto;
import com.epam.aidial.evaluation.runner.exception.TypeMismatchException;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ResponseColumnExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ValidationWarningsSerializer warningsSerializer = new ValidationWarningsSerializer(objectMapper);
    private final ResponseColumnTypeReconciler realReconciler = new ResponseColumnTypeReconciler();

    private ResponseColumnDefinitionDto column(String name, String expr, SchemaFieldType type) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .expression(expr)
                .type(type)
                .build();
    }

    @Test
    @DisplayName("ARRAY column + JSONata returning single match → singleton stored, no warning")
    void arrayColumnSingleMatchStoresSingleton() throws Exception {
        JsonataEvaluationService jsonata = mock(JsonataEvaluationService.class);
        when(jsonata.evaluate(anyString(), anyString(), any())).thenReturn("DECATHLON_map.pdf#page=1");

        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(jsonata, realReconciler, warningsSerializer, objectMapper);
        ResponseColumnExtractor.ExtractionResult result = extractor.extract(
                List.of(column("files", "$.files", SchemaFieldType.ARRAY)), "{\"files\":\"DECATHLON_map.pdf#page=1\"}");

        assertThat(objectMapper.readTree(result.extractedColumns()).get("files").isArray())
                .isTrue();
        assertThat(objectMapper
                        .readTree(result.extractedColumns())
                        .get("files")
                        .get(0)
                        .asString())
                .isEqualTo("DECATHLON_map.pdf#page=1");
        assertThat(warningsSerializer.deserializeExtractionWarnings(result.extractionWarnings()))
                .isEmpty();
    }

    @Test
    @DisplayName("STRING column + array result → null stored, warning with `expected STRING, got ARRAY`")
    void stringColumnArrayResultProducesWarning() throws Exception {
        JsonataEvaluationService jsonata = mock(JsonataEvaluationService.class);
        when(jsonata.evaluate(anyString(), anyString(), any())).thenReturn(List.of("a", "b"));

        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(jsonata, realReconciler, warningsSerializer, objectMapper);
        ResponseColumnExtractor.ExtractionResult result = extractor.extract(
                List.of(column("name", "$.items", SchemaFieldType.STRING)), "{\"items\":[\"a\",\"b\"]}");

        assertThat(objectMapper.readTree(result.extractedColumns()).get("name").isNull())
                .isTrue();
        List<ExtractionWarningDto> warnings =
                warningsSerializer.deserializeExtractionWarnings(result.extractionWarnings());
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getColumn()).isEqualTo("name");
        assertThat(warnings.get(0).getError()).isEqualTo("Type mismatch: expected STRING, got ARRAY");
    }

    @Test
    @DisplayName("NUMBER column + parseable string → coerced to Double silently, no warning")
    void numberColumnParseableStringCoerces() throws Exception {
        JsonataEvaluationService jsonata = mock(JsonataEvaluationService.class);
        when(jsonata.evaluate(anyString(), anyString(), any())).thenReturn("3.14");

        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(jsonata, realReconciler, warningsSerializer, objectMapper);
        ResponseColumnExtractor.ExtractionResult result =
                extractor.extract(List.of(column("score", "$.score", SchemaFieldType.NUMBER)), "{\"score\":\"3.14\"}");

        assertThat(objectMapper.readTree(result.extractedColumns()).get("score").asDouble())
                .isEqualTo(3.14);
        assertThat(warningsSerializer.deserializeExtractionWarnings(result.extractionWarnings()))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Catch-block propagation guard: TypeMismatchException from reconciler surfaces as ExtractionWarningDto")
    void typeMismatchFromReconcilerSurfacesAsWarning() throws Exception {
        JsonataEvaluationService jsonata = mock(JsonataEvaluationService.class);
        when(jsonata.evaluate(anyString(), anyString(), any())).thenReturn("anything");

        ResponseColumnTypeReconciler mockReconciler = mock(ResponseColumnTypeReconciler.class);
        when(mockReconciler.reconcile(any(), any()))
                .thenThrow(new TypeMismatchException(SchemaFieldType.OBJECT, "STRING"));

        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(jsonata, mockReconciler, warningsSerializer, objectMapper);
        ResponseColumnExtractor.ExtractionResult result =
                extractor.extract(List.of(column("payload", "$", SchemaFieldType.OBJECT)), "{\"x\":1}");

        // The exception MUST be caught by `catch (Exception ex)` and produce a warning, not propagate.
        assertThat(objectMapper
                        .readTree(result.extractedColumns())
                        .get("payload")
                        .isNull())
                .isTrue();
        List<ExtractionWarningDto> warnings =
                warningsSerializer.deserializeExtractionWarnings(result.extractionWarnings());
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getColumn()).isEqualTo("payload");
        assertThat(warnings.get(0).getExpression()).isEqualTo("$");
        assertThat(warnings.get(0).getError()).isEqualTo("Type mismatch: expected OBJECT, got STRING");
    }

    // --- WP4: $_request/$_response extraction frame tests ---

    private DashjoinJsonataEvaluationService realJsonata() {
        JsonataProperties properties = new JsonataProperties();
        properties.setEvaluationTimeoutMs(5000L);
        properties.setMaxRecursionDepth(500);
        return new DashjoinJsonataEvaluationService(objectMapper, properties);
    }

    @Test
    @DisplayName("$_request.<path> is reachable when requestBodyJson is provided")
    void requestFrameBindingReachableWhenRequestBodyProvided() throws Exception {
        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(realJsonata(), realReconciler, warningsSerializer, objectMapper);

        String requestBodyJson = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        ResponseColumnExtractor.ExtractionResult result = extractor.extract(
                List.of(column("firstUserMessage", "$_request.messages[0].content", SchemaFieldType.STRING)),
                "{\"choices\":[]}",
                requestBodyJson);

        assertThat(objectMapper
                        .readTree(result.extractedColumns())
                        .get("firstUserMessage")
                        .asString())
                .isEqualTo("hi");
        assertThat(warningsSerializer.deserializeExtractionWarnings(result.extractionWarnings()))
                .isEmpty();
    }

    @Test
    @DisplayName("$_response.<path> evaluates to the same value as the equivalent root-document path")
    void responseFrameBindingEqualsRootDocumentResult() throws Exception {
        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(realJsonata(), realReconciler, warningsSerializer, objectMapper);

        String responseBody = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
        ResponseColumnExtractor.ExtractionResult result = extractor.extract(
                List.of(
                        column("rootPath", "choices[0].message.content", SchemaFieldType.STRING),
                        column("viaResponseFrame", "$_response.choices[0].message.content", SchemaFieldType.STRING)),
                responseBody,
                null);

        JsonNode extracted = objectMapper.readTree(result.extractedColumns());
        assertThat(extracted.get("viaResponseFrame").asString())
                .isEqualTo(extracted.get("rootPath").asString());
        assertThat(warningsSerializer.deserializeExtractionWarnings(result.extractionWarnings()))
                .isEmpty();
    }

    @Test
    @DisplayName("root-document expression results are unchanged vs the 2-arg overload (frame is purely additive)")
    void rootDocumentExpressionUnchangedRegression() throws Exception {
        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(realJsonata(), realReconciler, warningsSerializer, objectMapper);

        String responseBody = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
        List<ResponseColumnDefinitionDto> columns =
                List.of(column("content", "choices[0].message.content", SchemaFieldType.STRING));

        ResponseColumnExtractor.ExtractionResult legacyResult = extractor.extract(columns, responseBody);
        ResponseColumnExtractor.ExtractionResult framedResult =
                extractor.extract(columns, responseBody, "{\"messages\":[]}");

        assertThat(framedResult.extractedColumns()).isEqualTo(legacyResult.extractedColumns());
        assertThat(framedResult.extractionWarnings()).isEqualTo(legacyResult.extractionWarnings());
    }

    @Test
    @DisplayName("null/blank/unparseable requestBodyJson leaves $_request unbound: referencing expression yields null,"
            + " no warning")
    void unboundRequestBindingYieldsNullWithNoWarning() throws Exception {
        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(realJsonata(), realReconciler, warningsSerializer, objectMapper);

        for (String requestBodyJson : new String[] {null, "", "   ", "{not valid json"}) {
            ResponseColumnExtractor.ExtractionResult result = extractor.extract(
                    List.of(column("firstMessage", "$_request.messages[0]", SchemaFieldType.OBJECT)),
                    "{\"choices\":[]}",
                    requestBodyJson);

            assertThat(objectMapper
                            .readTree(result.extractedColumns())
                            .get("firstMessage")
                            .isNull())
                    .as("requestBodyJson=%s", requestBodyJson)
                    .isTrue();
            assertThat(warningsSerializer.deserializeExtractionWarnings(result.extractionWarnings()))
                    .as("requestBodyJson=%s", requestBodyJson)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("ExtractionResult.values() matches the parsed extractedColumns JSON, including explicit null entries")
    void valuesMapMatchesParsedExtractedColumnsJson() throws Exception {
        ResponseColumnExtractor extractor =
                new ResponseColumnExtractor(realJsonata(), realReconciler, warningsSerializer, objectMapper);

        ResponseColumnExtractor.ExtractionResult result = extractor.extract(
                List.of(
                        column("content", "choices[0].message.content", SchemaFieldType.STRING),
                        column("missing", "choices[0].message.nonexistent", SchemaFieldType.STRING)),
                "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}",
                null);

        assertThat(result.values()).containsEntry("content", "hi");
        assertThat(result.values()).hasSize(2);
        assertThat(result.values().containsKey("missing")).isTrue();
        assertThat(result.values().get("missing")).isNull();

        JsonNode extracted = objectMapper.readTree(result.extractedColumns());
        assertThat(extracted.get("content").asString())
                .isEqualTo(result.values().get("content"));
        assertThat(extracted.get("missing").isNull()).isTrue();
    }
}
