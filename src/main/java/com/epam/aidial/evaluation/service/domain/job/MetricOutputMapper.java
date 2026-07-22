package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.dto.MetricErrorDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps metric evaluation results (per-TSMD) into metricValues and metricInfos ObjectNodes
 * for EvalSummary batch writing.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MetricOutputMapper {

    private final ObjectMapper objectMapper;

    /**
     * Builds the metricValues ObjectNode from TSMD evaluation results.
     *
     * <p>For each TSMD:
     * <ul>
     *   <li>Success: each output field → numeric value or null (for error fields)</li>
     *   <li>Failure with field names: one null entry per output field</li>
     *   <li>Failure with empty field names: empty object {} (defense-in-depth fallback)</li>
     * </ul>
     *
     * @param tsmdResults map of TSMD name → TsmdEvaluationResult
     * @return ObjectNode with nested structure: {tsmdName: {fieldName: number|null}}
     */
    public ObjectNode buildMetricValues(Map<String, TsmdEvaluationResult> tsmdResults) {
        ObjectNode root = objectMapper.createObjectNode();
        for (Map.Entry<String, TsmdEvaluationResult> entry : tsmdResults.entrySet()) {
            String tsmdName = entry.getKey();

            switch (entry.getValue()) {
                case TsmdEvaluationResult.Success(var response, var _) -> {
                    ObjectNode tsmdNode = objectMapper.createObjectNode();
                    mapResponseValues(tsmdNode, response.getOutput());
                    root.set(tsmdName, tsmdNode);
                }
                case TsmdEvaluationResult.Failure(var _, var fieldsNames) -> {
                    ObjectNode tsmdNode = objectMapper.createObjectNode();
                    if (fieldsNames.isEmpty()) {
                        log.warn("TSMD '{}' has no output field names — producing empty metricValues", tsmdName);
                    } else {
                        for (String fieldName : fieldsNames) {
                            tsmdNode.putNull(fieldName);
                        }
                    }
                    root.set(tsmdName, tsmdNode);
                }
                case TsmdEvaluationResult.ConditionError _ -> {
                    /* no metricValues entry */
                }
            }
        }
        return root;
    }

    /**
     * Builds the metricInfos ObjectNode from TSMD evaluation results.
     *
     * <p>For each TSMD:
     * <ul>
     *   <li>Success with details: {fieldName: details object}</li>
     *   <li>Success error output: {fieldName: {"error": message}}</li>
     *   <li>Failure with field names: {fieldName: {"error": exceptionMessage}} per field</li>
     *   <li>Failure with empty field names: {"error": exceptionMessage} (fallback, no field wrapper)</li>
     * </ul>
     *
     * @param tsmdResults map of TSMD name → TsmdEvaluationResult
     * @return ObjectNode, or null if all TSMDs have no info entries
     */
    public ObjectNode buildMetricInfos(Map<String, TsmdEvaluationResult> tsmdResults) {
        ObjectNode root = objectMapper.createObjectNode();
        boolean hasAnyInfo = false;

        for (Map.Entry<String, TsmdEvaluationResult> entry : tsmdResults.entrySet()) {
            String tsmdName = entry.getKey();
            switch (entry.getValue()) {
                case TsmdEvaluationResult.Success(var response, var _) -> {
                    ObjectNode tsmdInfoNode = buildResponseInfos(response.getOutput());
                    if (tsmdInfoNode != null) {
                        root.set(tsmdName, tsmdInfoNode);
                        hasAnyInfo = true;
                    }
                }
                case TsmdEvaluationResult.Failure(var error, var fieldsNames) -> {
                    String message = error.getMessage();
                    ObjectNode tsmdInfoNode = objectMapper.createObjectNode();
                    if (fieldsNames.isEmpty()) {
                        tsmdInfoNode.put("error", message);
                    } else {
                        for (String fieldName : fieldsNames) {
                            ObjectNode errorNode = objectMapper.createObjectNode();
                            errorNode.put("error", message);
                            tsmdInfoNode.set(fieldName, errorNode);
                        }
                    }
                    root.set(tsmdName, tsmdInfoNode);
                    hasAnyInfo = true;
                }
                case TsmdEvaluationResult.ConditionError(var message, var _) -> {
                    ObjectNode tsmdInfoNode = objectMapper.createObjectNode();
                    tsmdInfoNode.put("error", message);
                    root.set(tsmdName, tsmdInfoNode);
                    hasAnyInfo = true;
                }
            }
        }

        return hasAnyInfo ? root : null;
    }

    private void mapResponseValues(ObjectNode tsmdNode, Map<String, MetricOutputDto> output) {
        if (output == null) {
            return;
        }
        for (Map.Entry<String, MetricOutputDto> outputEntry : output.entrySet()) {
            String fieldName = outputEntry.getKey();
            MetricOutputDto field = outputEntry.getValue();

            if (field instanceof MetricOutputFieldDto valueField) {
                tsmdNode.put(fieldName, valueField.getValue());
            } else {
                tsmdNode.putNull(fieldName);
            }
        }
    }

    private ObjectNode buildResponseInfos(Map<String, MetricOutputDto> output) {
        if (output == null) {
            return null;
        }
        ObjectNode tsmdInfoNode = objectMapper.createObjectNode();
        boolean hasInfo = false;

        for (Map.Entry<String, MetricOutputDto> outputEntry : output.entrySet()) {
            String fieldName = outputEntry.getKey();
            MetricOutputDto field = outputEntry.getValue();

            if (field instanceof MetricOutputFieldDto valueField
                    && valueField.getDetails() != null
                    && !valueField.getDetails().isEmpty()) {
                JsonNode detailsNode = objectMapper.convertValue(valueField.getDetails(), JsonNode.class);
                tsmdInfoNode.set(fieldName, detailsNode);
                hasInfo = true;
            } else if (field instanceof MetricErrorDto error) {
                ObjectNode errorNode = objectMapper.createObjectNode();
                errorNode.put("error", error.getMessage());
                tsmdInfoNode.set(fieldName, errorNode);
                hasInfo = true;
            }
        }

        return hasInfo ? tsmdInfoNode : null;
    }
}
