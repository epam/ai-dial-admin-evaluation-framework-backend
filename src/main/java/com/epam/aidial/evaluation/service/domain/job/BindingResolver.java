package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBindingSourceDto;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves TSMD config/input bindings against test case data and extracted columns.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class BindingResolver {

    private static final TypeReference<List<MetricParameterBindingDto>> BINDING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /**
     * Parses a JSON string of metric parameter bindings.
     *
     * @param bindingsJson JSON array string (from configBindings or inputBindings)
     * @return parsed list, or empty list if null/empty
     */
    public List<MetricParameterBindingDto> parseBindings(String bindingsJson) {
        if (bindingsJson == null || bindingsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(bindingsJson, BINDING_LIST_TYPE);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to parse metric parameter bindings: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a JSON string into a map (for testCaseData or extractedColumns).
     *
     * @param json JSON object string
     * @return parsed map, or empty map if null/empty
     */
    public Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to parse JSON map: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves bindings against test case data and extracted columns.
     *
     * @param bindings         list of parameter bindings (from TSMD configBindings or inputBindings)
     * @param testCaseData     parsed test case data map (from TestCaseRunResult.testCaseData)
     * @param extractedColumns parsed extracted columns map (from TestCaseRunResult.extractedColumns)
     * @return map of property name → resolved value (null if column is missing)
     */
    public Map<String, Object> resolveBindings(
            List<MetricParameterBindingDto> bindings,
            Map<String, Object> testCaseData,
            Map<String, Object> extractedColumns) {
        if (bindings == null || bindings.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (MetricParameterBindingDto binding : bindings) {
            Object value = resolveSource(binding.getSource(), testCaseData, extractedColumns);
            resolved.put(binding.getProperty(), value);
        }
        return resolved;
    }

    private Object resolveSource(
            MetricBindingSourceDto source, Map<String, Object> testCaseData, Map<String, Object> extractedColumns) {
        if (source instanceof TestCaseBindingSourceDto testCaseSource) {
            String columnName = testCaseSource.getColumnName();
            if (!testCaseData.containsKey(columnName)) {
                throw new IllegalArgumentException(
                        "TestCase binding references missing column '" + columnName + "' in test case data");
            }
            return testCaseData.get(columnName);
        } else if (source instanceof ResponseBindingSourceDto responseSource) {
            String columnName = responseSource.getColumnName();
            if (!extractedColumns.containsKey(columnName)) {
                throw new IllegalArgumentException(
                        "Response binding references missing column '" + columnName + "' in extracted columns");
            }
            return extractedColumns.get(columnName);
        } else if (source instanceof ConstantBindingSourceDto constantSource) {
            return constantSource.getValue();
        }
        throw new IllegalArgumentException(
                "Unknown binding source type: " + source.getClass().getSimpleName());
    }
}
