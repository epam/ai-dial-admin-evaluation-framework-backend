package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExtractionWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.TypeMismatchException;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        when(jsonata.evaluate(anyString(), anyString())).thenReturn("DECATHLON_map.pdf#page=1");

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
                        .asText())
                .isEqualTo("DECATHLON_map.pdf#page=1");
        assertThat(warningsSerializer.deserializeExtractionWarnings(result.extractionWarnings()))
                .isEmpty();
    }

    @Test
    @DisplayName("STRING column + array result → null stored, warning with `expected STRING, got ARRAY`")
    void stringColumnArrayResultProducesWarning() throws Exception {
        JsonataEvaluationService jsonata = mock(JsonataEvaluationService.class);
        when(jsonata.evaluate(anyString(), anyString())).thenReturn(List.of("a", "b"));

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
        when(jsonata.evaluate(anyString(), anyString())).thenReturn("3.14");

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
        when(jsonata.evaluate(anyString(), anyString())).thenReturn("anything");

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
}
