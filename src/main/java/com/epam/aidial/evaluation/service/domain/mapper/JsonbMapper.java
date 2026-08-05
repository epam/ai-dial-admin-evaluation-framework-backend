package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.OverallScoreDefinition;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps every JSONB-backed field on {@code TestSuite}/{@code Dataset}/{@code TestSuiteMetricDefinition} and
 * their DTOs, except the two execution-path fields ({@code requestTemplate}, {@code inputBindings}) whose
 * read direction is owned by the shared module's {@link RunnerJsonbMapper} and delegated to here, so
 * there is a single source of truth for parsing those two fields (see Decision 10 in the
 * {@code evaluation-runner-core-module} change's {@code design.md}).
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class JsonbMapper {

    private static final TypeReference<List<FieldDefinitionDto>> FIELD_DEF_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ResponseColumnDefinitionDto>> RESPONSE_COL_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<MetricParameterBindingDto>> METRIC_BINDING_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;
    private final RunnerJsonbMapper runnerJsonbMapper;

    public RequestTemplateDto mapRequestTemplate(String json) {
        return runnerJsonbMapper.mapRequestTemplate(json);
    }

    public List<InputBindingDto> mapInputBindings(String json) {
        return runnerJsonbMapper.mapInputBindings(json);
    }

    public String mapInputBindings(List<InputBindingDto> value) {
        return writeList(value, "inputBindings");
    }

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

    public String mapJsonSchema(Map<String, Object> value) {
        return write(value, "jsonSchema");
    }

    public Map<String, Object> mapJsonSchema(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Failed to deserialize jsonSchema", ex);
        }
    }

    /**
     * Serializes the per-suite {@code overall} score definition from the request DTO into the entity's
     * JSONB column. Returns {@code null} for a null input so the column stays null (meaning "use the
     * system default").
     */
    public String mapOverallScore(OverallScoreDefinition value) {
        return write(value, "overallScore");
    }

    /**
     * The per-suite {@code overall} score definition, read from the entity into the suite snapshot or the
     * suite response. A null column means the run-level {@code overall} falls back to the system default.
     */
    public OverallScoreDefinition mapOverallScore(String json) {
        return read(json, OverallScoreDefinition.class, "overallScore");
    }

    /**
     * Serializes the per-suite {@code testCaseFilter} (an opaque Structured Query DSL filter subtree)
     * from the request DTO into the entity's JSONB column. Returns {@code null} for a null input so the
     * column stays null (meaning "no filter").
     */
    public String mapTestCaseFilter(Map<String, Object> value) {
        return write(value, "testCaseFilter");
    }

    /**
     * The per-suite {@code testCaseFilter} (an opaque filter subtree), read from the entity into the
     * suite response. A null column means the suite applies no test-case filter.
     */
    public Map<String, Object> mapTestCaseFilter(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Failed to deserialize testCaseFilter", ex);
        }
    }

    private String write(Object value, String label) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Failed to serialize " + label, ex);
        }
    }

    private <T> String writeList(List<T> value, String label) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Failed to serialize " + label, ex);
        }
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
