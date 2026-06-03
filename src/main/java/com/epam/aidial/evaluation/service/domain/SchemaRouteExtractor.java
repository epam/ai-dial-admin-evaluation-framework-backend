package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreSchemaRouteDto;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ApplicationRouteDto;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class SchemaRouteExtractor {

    private static final String APPLICATION_TYPE_ROUTES_KEY = "dial:applicationTypeRoutes";
    private static final TypeReference<Map<String, DialCoreSchemaRouteDto>> SCHEMA_ROUTES_TYPE =
            new TypeReference<>() {};

    private final DialCoreClient dialCoreClient;
    private final ObjectMapper objectMapper;
    private final DeploymentMapper deploymentMapper;

    public Map<String, ApplicationRouteDto> resolveRoutes(DialCoreApplicationDto app) {
        String schemaId = app.getApplicationTypeSchemaId();
        if (schemaId == null) {
            return null;
        }
        try {
            JsonNode schema = dialCoreClient.getApplicationTypeSchema(schemaId);
            JsonNode routesNode = schema.get(APPLICATION_TYPE_ROUTES_KEY);
            if (routesNode == null || routesNode.isNull() || routesNode.isMissingNode()) {
                return null;
            }
            Map<String, DialCoreSchemaRouteDto> schemaRoutes =
                    objectMapper.convertValue(routesNode, SCHEMA_ROUTES_TYPE);
            Map<String, ApplicationRouteDto> result = new LinkedHashMap<>();
            for (Map.Entry<String, DialCoreSchemaRouteDto> entry : schemaRoutes.entrySet()) {
                ApplicationRouteDto dto = deploymentMapper.toApplicationRouteDto(entry.getValue());
                dto.setName(entry.getKey());
                result.put(entry.getKey(), dto);
            }
            mergeAppRoutes(result, app.getRoutes(), schemaId);
            return result;
        } catch (Exception e) {
            log.warn("Failed to resolve schema routes for schemaId={}: {}", schemaId, e.getMessage(), e);
            return null;
        }
    }

    private void mergeAppRoutes(
            Map<String, ApplicationRouteDto> base, Map<String, DialCoreRouteDto> appRoutes, String schemaId) {
        if (appRoutes == null) {
            return;
        }
        for (Map.Entry<String, DialCoreRouteDto> entry : appRoutes.entrySet()) {
            String key = entry.getKey();
            if (base.containsKey(key)) {
                log.warn("Route key '{}' from app overrides schema route for schemaId={}", key, schemaId);
            }
            ApplicationRouteDto dto = deploymentMapper.toApplicationRouteDto(entry.getValue());
            dto.setName(key);
            base.put(key, dto);
        }
    }
}
