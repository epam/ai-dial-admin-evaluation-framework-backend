package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Normalizes a result's {@code extractedColumns} value to a single object at the result→metric boundary.
 *
 * <p>Multi-step ({@code multiStep == true}) runs persist {@code extractedColumns} as a JSON <b>array</b> of
 * per-step maps, while single-step runs persist a JSON <b>object</b>. Metric scoring always operates on the
 * <b>last</b> step. This component applies shape detection so both metric call sites stay consistent
 * (see design D6):
 * <ul>
 *   <li>JSON array → its last element ({@code array[n-1]});</li>
 *   <li>empty array ({@code n == 0}, e.g. a conversation that failed at step 0) → empty JSON object {@code {}}
 *       (never an exception, never an array);</li>
 *   <li>JSON object → unchanged.</li>
 * </ul>
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ExtractedColumnsNormalizer {

    private final ObjectMapper objectMapper;

    /**
     * Normalizes an already-parsed {@code extractedColumns} node by shape.
     *
     * @param node parsed extractedColumns (may be null)
     * @return the last array element, an empty object for an empty/absent array, or the node unchanged
     */
    public JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (node.isArray()) {
            return node.isEmpty() ? objectMapper.createObjectNode() : node.get(node.size() - 1);
        }
        return node;
    }

    /**
     * Parses a raw {@code extractedColumns} JSON string, normalizes it by shape, and returns the normalized
     * JSON <b>object</b> string. Null/blank input is returned unchanged so downstream null handling is
     * preserved; unparseable input is returned unchanged so downstream error handling is unchanged.
     *
     * @param json raw extractedColumns JSON string (may be null/blank)
     * @return normalized object JSON string (or the original for null/blank/unparseable input)
     */
    public String normalizeToJsonString(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            return objectMapper.writeValueAsString(normalize(objectMapper.readTree(json)));
        } catch (JacksonException e) {
            log.warn("Failed to normalize extractedColumns JSON; passing through unchanged: {}", e.getMessage(), e);
            return json;
        }
    }
}
