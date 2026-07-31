package com.epam.aidial.evaluation.runner.util;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Deserializes the two JSONB-backed fields the execution path reads at runtime: a test case's
 * request-template and input-binding overrides. Every other suite/dataset/metric JSONB field is owned by
 * the EF backend's own {@code service.domain.mapper.JsonbMapper}, which delegates here for these two
 * methods (see Decision 10 in the {@code evaluation-runner-core-module} change's {@code design.md}).
 *
 * <p>Named {@code RunnerJsonbMapper} (bean name {@code "runnerJsonbMapper"}) rather than plain
 * {@code JsonbMapper} so the EF backend's own {@code service.domain.mapper.JsonbMapper} can inject this
 * class by simple name/import instead of a fully-qualified reference.
 */
@Component("runnerJsonbMapper")
@LogExecution
@RequiredArgsConstructor
public class RunnerJsonbMapper {

    private static final TypeReference<List<InputBindingDto>> BINDING_LIST_TYPE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public RequestTemplateDto mapRequestTemplate(String json) {
        return read(json, RequestTemplateDto.class, "requestTemplate");
    }

    public List<InputBindingDto> mapInputBindings(String json) {
        return readList(json, BINDING_LIST_TYPE, "inputBindings");
    }

    private <T> T read(String json, Class<T> type, String label) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Failed to deserialize " + label, ex);
        }
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type, String label) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> result = objectMapper.readValue(json, type);
            return result != null ? result : List.of();
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Failed to deserialize " + label, ex);
        }
    }
}
