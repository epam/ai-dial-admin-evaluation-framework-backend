package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExtractionWarningDto;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Every expression additionally evaluates with a JSONata frame carrying {@code $response} (the
 * parsed response body) and {@code $request} (the parsed request body actually sent, when available) as
 * named variable bindings. The root document (positional {@code $}) stays the raw response body
 * unchanged, so every pre-existing response-column expression keeps its exact prior behavior;
 * {@code $request}/{@code $response} are purely additive access points. A binding is omitted entirely
 * (left unbound, not bound to JSON null) when its source JSON is null, blank, or fails to parse — an
 * expression referencing an omitted binding (e.g. {@code $request.messages} with no request body) sees
 * the same {@code undefined} result as referencing any other unbound variable, and any resulting
 * extraction failure is covered by the existing per-column try/catch below.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ResponseColumnExtractor {

    private static final String RESPONSE_BINDING = "response";
    private static final String REQUEST_BINDING = "request";

    private final JsonataEvaluationService jsonataEvaluationService;
    private final ResponseColumnTypeReconciler typeReconciler;
    private final ValidationWarningsSerializer warningsSerializer;
    private final ObjectMapper objectMapper;

    /**
     * Extraction result containing serialized JSON strings for persistence, plus the reconciled
     * per-column values (exactly as persisted in {@code extractedColumns}, including explicit {@code
     * null} entries for failed or type-mismatched columns) for callers that need the plain-Java values
     * rather than the serialized JSON form.
     */
    public record ExtractionResult(String extractedColumns, String extractionWarnings, Map<String, Object> values) {}

    /**
     * Evaluates all response column expressions against the given response body JSON. Equivalent to
     * calling {@link #extract(List, String, String)} with a null {@code requestBodyJson} (no
     * {@code $request} frame binding).
     *
     * @param responseColumns list of column definitions (may be null or empty)
     * @param responseBody    JSON response body string (may be null or blank)
     * @return extraction result with serialized JSON strings
     */
    public ExtractionResult extract(List<ResponseColumnDefinitionDto> responseColumns, String responseBody) {
        return extract(responseColumns, responseBody, null);
    }

    /**
     * Evaluates all response column expressions against the given response body JSON, with
     * {@code $request}/{@code $response} frame bindings available to every expression.
     *
     * @param responseColumns list of column definitions (may be null or empty)
     * @param responseBody    JSON response body string (may be null or blank); also the root document
     *                        every expression evaluates against, unchanged from before this frame was
     *                        introduced
     * @param requestBodyJson JSON of the request body actually sent (may be null, blank, or
     *                        unparseable, in which case the {@code $request} binding is left unbound)
     * @return extraction result with serialized JSON strings and reconciled per-column values
     */
    public ExtractionResult extract(
            List<ResponseColumnDefinitionDto> responseColumns, String responseBody, String requestBodyJson) {
        if (responseColumns == null || responseColumns.isEmpty()) {
            return new ExtractionResult("{}", "[]", Map.of());
        }

        ObjectNode extracted = objectMapper.createObjectNode();
        Map<String, Object> values = new LinkedHashMap<>();
        List<ExtractionWarningDto> warnings = new ArrayList<>();

        if (responseBody == null || responseBody.isBlank()) {
            for (ResponseColumnDefinitionDto col : responseColumns) {
                extracted.putNull(col.getName());
                values.put(col.getName(), null);
                warnings.add(ExtractionWarningDto.builder()
                        .column(col.getName())
                        .expression(col.getExpression())
                        .error("Response body is null or absent")
                        .build());
            }
            return serialize(extracted, warnings, values);
        }

        Map<String, Object> frameBindings = buildFrameBindings(responseBody, requestBodyJson);

        for (ResponseColumnDefinitionDto col : responseColumns) {
            try {
                Object value = jsonataEvaluationService.evaluate(col.getExpression(), responseBody, frameBindings);
                Object reconciled = typeReconciler.reconcile(value, col.getType());
                values.put(col.getName(), reconciled);
                if (reconciled == null) {
                    extracted.putNull(col.getName());
                } else {
                    extracted.set(col.getName(), objectMapper.convertValue(reconciled, JsonNode.class));
                }
            } catch (Exception ex) {
                log.warn("Extraction failed for column '{}': {}", col.getName(), ex.getMessage(), ex);
                extracted.putNull(col.getName());
                values.put(col.getName(), null);
                warnings.add(ExtractionWarningDto.builder()
                        .column(col.getName())
                        .expression(col.getExpression())
                        .error(ex.getMessage())
                        .build());
            }
        }

        return serialize(extracted, warnings, values);
    }

    /**
     * Builds the {@code $response}/{@code $request} frame bindings shared by every column's evaluation
     * in one extraction call. A name is omitted from the map (left unbound) rather than bound to a Java
     * {@code null} when its source JSON is null, blank, or fails to parse, so referencing it evaluates
     * to {@code undefined} instead of an explicit JSON null.
     */
    private Map<String, Object> buildFrameBindings(String responseBody, String requestBodyJson) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        putParsedIfPresent(bindings, RESPONSE_BINDING, responseBody);
        putParsedIfPresent(bindings, REQUEST_BINDING, requestBodyJson);
        return bindings;
    }

    private void putParsedIfPresent(Map<String, Object> bindings, String name, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            bindings.put(name, objectMapper.readValue(json, Object.class));
        } catch (JacksonException ex) {
            log.warn("Failed to parse {} JSON for frame binding: {}", name, ex.getMessage(), ex);
        }
    }

    private ExtractionResult serialize(
            ObjectNode extracted, List<ExtractionWarningDto> warnings, Map<String, Object> values) {
        String extractedJson;
        try {
            extractedJson = objectMapper.writeValueAsString(extracted);
        } catch (JacksonException ex) {
            log.error("Failed to serialize extracted columns: {}", ex.getMessage(), ex);
            throw new IllegalStateException("Failed to serialize extracted columns", ex);
        }
        String warningsJson = warningsSerializer.serializeExtractionWarnings(warnings);
        return new ExtractionResult(extractedJson, warningsJson, values);
    }
}
