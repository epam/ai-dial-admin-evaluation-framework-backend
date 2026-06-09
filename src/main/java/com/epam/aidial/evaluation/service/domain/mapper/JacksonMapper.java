package com.epam.aidial.evaluation.service.domain.mapper;

import lombok.RequiredArgsConstructor;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class JacksonMapper {

    private final ObjectMapper objectMapper;

    public String asString(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(jsonNode);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    public JsonNode asJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize JSON", e);
        }
    }

    @Named("serializeLogDetails")
    public String serializeLogDetails(Object logDetails) {
        if (logDetails == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(logDetails);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize logDetails", e);
        }
    }

    @Named("deserializeLogDetails")
    public Object deserializeLogDetails(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize logDetails", e);
        }
    }
}
