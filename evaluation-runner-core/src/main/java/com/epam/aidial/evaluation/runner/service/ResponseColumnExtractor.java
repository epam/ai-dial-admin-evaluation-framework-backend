package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.analytics.ExtractionWarningDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evaluates response column JSONata expressions against a JSON response body string
 * and produces serialized extracted-columns and extraction-warnings strings ready for persistence.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ResponseColumnExtractor {

    private final JsonataEvaluationService jsonataEvaluationService;
    private final ResponseColumnTypeReconciler typeReconciler;
    private final ValidationWarningsSerializer warningsSerializer;
    private final ObjectMapper objectMapper;

    /**
     * Extraction result containing serialized JSON strings for persistence.
     */
    public record ExtractionResult(String extractedColumns, String extractionWarnings) {}

    /**
     * Evaluates all response column expressions against the given response body JSON.
     *
     * @param responseColumns list of column definitions (may be null or empty)
     * @param responseBody    JSON response body string (may be null or blank)
     * @return extraction result with serialized JSON strings
     */
    public ExtractionResult extract(List<ResponseColumnDefinitionDto> responseColumns, String responseBody) {
        if (responseColumns == null || responseColumns.isEmpty()) {
            return new ExtractionResult("{}", "[]");
        }

        ObjectNode extracted = objectMapper.createObjectNode();
        List<ExtractionWarningDto> warnings = new ArrayList<>();

        if (responseBody == null || responseBody.isBlank()) {
            for (ResponseColumnDefinitionDto col : responseColumns) {
                extracted.putNull(col.getName());
                warnings.add(ExtractionWarningDto.builder()
                        .column(col.getName())
                        .expression(col.getExpression())
                        .error("Response body is null or absent")
                        .build());
            }
            return serialize(extracted, warnings);
        }

        for (ResponseColumnDefinitionDto col : responseColumns) {
            try {
                Object value = jsonataEvaluationService.evaluate(col.getExpression(), responseBody);
                Object reconciled = typeReconciler.reconcile(value, col.getType());
                if (reconciled == null) {
                    extracted.putNull(col.getName());
                } else {
                    extracted.set(col.getName(), objectMapper.convertValue(reconciled, JsonNode.class));
                }
            } catch (Exception ex) {
                log.warn("Extraction failed for column '{}': {}", col.getName(), ex.getMessage(), ex);
                extracted.putNull(col.getName());
                warnings.add(ExtractionWarningDto.builder()
                        .column(col.getName())
                        .expression(col.getExpression())
                        .error(ex.getMessage())
                        .build());
            }
        }

        return serialize(extracted, warnings);
    }

    private ExtractionResult serialize(ObjectNode extracted, List<ExtractionWarningDto> warnings) {
        String extractedJson;
        try {
            extractedJson = objectMapper.writeValueAsString(extracted);
        } catch (JacksonException ex) {
            log.error("Failed to serialize extracted columns: {}", ex.getMessage(), ex);
            throw new IllegalStateException("Failed to serialize extracted columns", ex);
        }
        String warningsJson = warningsSerializer.serializeExtractionWarnings(warnings);
        return new ExtractionResult(extractedJson, warningsJson);
    }
}
