package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-conversation, column-major accumulator for multi-step response extractions. Each completed step's
 * extracted-columns JSON object is transposed into per-column arrays: for every response column, the step's
 * value is appended to that column's array ({@code null} when the step lacked it or its JSON was malformed),
 * so all columns stay aligned to the completed-step count. {@link #toJson()} renders the result as a single
 * column-major object {@code {col: [step0, step1, ...]}} ({@code {}} when no steps were accumulated).
 *
 * <p>Not a Spring bean — it holds mutable per-conversation state and is instantiated once per {@code execute()}.
 */
@Slf4j
public class MultiStepColumnAccumulator {

    private final ObjectMapper objectMapper;
    private final Map<String, ArrayNode> columnArrays = new LinkedHashMap<>();

    public MultiStepColumnAccumulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void addStep(List<ResponseColumnDefinitionDto> responseColumns, String stepExtractedColumnsJson) {
        if (responseColumns == null || responseColumns.isEmpty()) {
            return;
        }
        final JsonNode stepObject = readTree(stepExtractedColumnsJson);
        for (ResponseColumnDefinitionDto column : responseColumns) {
            final ArrayNode columnArray =
                    columnArrays.computeIfAbsent(column.getName(), name -> objectMapper.createArrayNode());
            final JsonNode value = stepObject.get(column.getName());
            if (value == null || value.isNull()) {
                columnArray.addNull();
            } else {
                columnArray.add(value);
            }
        }
    }

    public String toJson() {
        final ObjectNode node = objectMapper.createObjectNode();
        columnArrays.forEach(node::set);
        return serialize(node);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            log.warn("Failed to parse extracted columns JSON: {}", e.getMessage(), e);
            return objectMapper.createObjectNode();
        }
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            return body.toString();
        }
    }
}
