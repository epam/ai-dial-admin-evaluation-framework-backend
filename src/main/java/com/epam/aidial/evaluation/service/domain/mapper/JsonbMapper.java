package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ToolReferenceDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class JsonbMapper {

    private static final TypeReference<List<FieldDefinitionDto>> FIELD_DEF_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<InputBindingDto>> BINDING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ResponseColumnDefinitionDto>> RESPONSE_COL_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<MetricParameterBindingDto>> METRIC_BINDING_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public String map(DeploymentReferenceDto value) {
        return write(value, "deploymentRef");
    }

    public DeploymentReferenceDto map(String json) {
        return read(json, DeploymentReferenceDto.class, "deploymentRef");
    }

    public String map(EndpointContractDto value) {
        return write(value, "endpointRef");
    }

    public String map(RequestTemplateDto value) {
        return write(value, "requestTemplate");
    }

    public String mapFieldDefinitions(List<FieldDefinitionDto> value) {
        return writeList(value, "testCaseSchema");
    }

    public List<FieldDefinitionDto> mapFieldDefinitions(String json) {
        return readList(json, FIELD_DEF_LIST_TYPE, "testCaseSchema");
    }

    public String mapInputBindings(List<InputBindingDto> value) {
        return writeList(value, "inputBindings");
    }

    public List<InputBindingDto> mapInputBindings(String json) {
        return readList(json, BINDING_LIST_TYPE, "inputBindings");
    }

    public String mapResponseColumns(List<ResponseColumnDefinitionDto> value) {
        return writeList(value, "responseColumns");
    }

    public List<ResponseColumnDefinitionDto> mapResponseColumns(String json) {
        return readList(json, RESPONSE_COL_LIST_TYPE, "responseColumns");
    }

    public String mapMetricBindings(List<MetricParameterBindingDto> value) {
        return writeList(value, "metricBindings");
    }

    public List<MetricParameterBindingDto> mapMetricBindings(String json) {
        return readList(json, METRIC_BINDING_LIST_TYPE, "metricBindings");
    }

    public String mapMcpDeploymentRef(McpDeploymentReferenceDto value) {
        return write(value, "mcpDeploymentRef");
    }

    public McpDeploymentReferenceDto mapMcpDeploymentRef(String json) {
        return read(json, McpDeploymentReferenceDto.class, "mcpDeploymentRef");
    }

    public String mapToolRef(ToolReferenceDto value) {
        return write(value, "toolRef");
    }

    public ToolReferenceDto mapToolRef(String json) {
        return read(json, ToolReferenceDto.class, "toolRef");
    }

    public String mapArgumentTemplate(ArgumentTemplateDto value) {
        return write(value, "argumentTemplate");
    }

    public ArgumentTemplateDto mapArgumentTemplate(String json) {
        return read(json, ArgumentTemplateDto.class, "argumentTemplate");
    }

    public EndpointContractDto mapEndpointContract(String json) {
        return read(json, EndpointContractDto.class, "endpointRef");
    }

    public RequestTemplateDto mapRequestTemplate(String json) {
        return read(json, RequestTemplateDto.class, "requestTemplate");
    }

    public String mapJsonSchema(Map<String, Object> value) {
        return write(value, "jsonSchema");
    }

    public Map<String, Object> mapJsonSchema(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to deserialize jsonSchema", ex);
        }
    }

    /**
     * Extracts deployment ID from serialized deploymentRef JSONB.
     */
    public String extractDeploymentId(String deploymentRefJson) {
        if (deploymentRefJson == null || deploymentRefJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(deploymentRefJson);
            JsonNode idNode = node.get("id");
            return idNode != null ? idNode.asText() : null;
        } catch (JsonProcessingException e) {
            log.warn("Failed to extract deploymentId: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extracts HTTP method from serialized endpointRef JSONB.
     * Defaults to POST if not specified.
     */
    public HttpMethod extractHttpMethod(String endpointRefJson) {
        if (endpointRefJson == null || endpointRefJson.isBlank()) {
            return HttpMethod.POST;
        }
        try {
            JsonNode node = objectMapper.readTree(endpointRefJson);
            JsonNode methodNode = node.get("method");
            if (methodNode != null && !methodNode.isNull()) {
                return HttpMethod.valueOf(methodNode.asText().toUpperCase());
            }
            return HttpMethod.POST;
        } catch (JsonProcessingException e) {
            log.warn("Failed to extract HTTP method: {}", e.getMessage(), e);
            return HttpMethod.POST;
        }
    }

    private String write(Object value, String label) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize " + label, ex);
        }
    }

    private <T> String writeList(List<T> value, String label) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize " + label, ex);
        }
    }

    private <T> T read(String json, Class<T> type, String label) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
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
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to deserialize " + label, ex);
        }
    }
}
