package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteMapper {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final JsonbMapper jsonbMapper;
    private final ValidationWarningsSerializer warningsSerializer;
    private final ObjectMapper objectMapper;

    public TestSuiteResponseDto toDto(TestSuite entity) {
        if (entity == null) {
            return null;
        }
        EndpointContractDto endpointRef = jsonbMapper.mapEndpointContract(entity.getEndpointRef());
        RequestTemplateDto requestTemplate = jsonbMapper.mapRequestTemplate(entity.getRequestTemplate());
        List<ResponseColumnDefinitionDto> responseColumns = jsonbMapper.mapResponseColumns(entity.getResponseColumns());
        List<InputBindingDto> inputBindings = jsonbMapper.mapInputBindings(entity.getInputBindings());
        McpDeploymentReferenceDto mcpDeploymentRef = jsonbMapper.mapMcpDeploymentRef(entity.getMcpDeploymentRef());
        ToolReferenceDto toolRef = jsonbMapper.mapToolRef(entity.getToolRef());
        ArgumentTemplateDto argumentTemplate = jsonbMapper.mapArgumentTemplate(entity.getArgumentTemplate());
        List<ValidationWarningDto> validationWarnings =
                warningsSerializer.deserializeWarnings(entity.getValidationWarnings());

        SuiteType suiteType = entity.getSuiteType() != null ? entity.getSuiteType() : SuiteType.DEPLOYMENT;

        return TestSuiteResponseDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .suiteType(suiteType)
                .datasetId(entity.getDatasetId())
                .disabledTestCaseIds(deserializeDisabledIds(entity.getDisabledTestCaseIds()))
                .deploymentRef(jsonbMapper.map(entity.getDeploymentRef()))
                .endpointRef(endpointRef)
                .responseColumns(responseColumns)
                .requestTemplate(requestTemplate)
                .inputBindings(inputBindings)
                .mcpDeploymentRef(mcpDeploymentRef)
                .toolRef(toolRef)
                .argumentTemplate(argumentTemplate)
                .valid(entity.isValid())
                .validationWarnings(validationWarnings)
                .version(entity.getVersion())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public TestSuite toEntity(TestSuiteRequestDto dto, String createdBy) {
        if (dto == null) {
            return null;
        }
        SuiteType suiteType = dto.getSuiteType() != null ? dto.getSuiteType() : SuiteType.DEPLOYMENT;
        return TestSuite.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .suiteType(suiteType)
                .datasetId(dto.getDatasetId())
                .disabledTestCaseIds(serializeDisabledIds(dto.getDisabledTestCaseIds()))
                .deploymentRef(jsonbMapper.map(dto.getDeploymentRef()))
                .endpointRef(jsonbMapper.map(dto.getEndpointRef()))
                .responseColumns(jsonbMapper.mapResponseColumns(dto.getResponseColumns()))
                .requestTemplate(jsonbMapper.map(dto.getRequestTemplate()))
                .inputBindings(jsonbMapper.mapInputBindings(dto.getInputBindings()))
                .mcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(dto.getMcpDeploymentRef()))
                .toolRef(jsonbMapper.mapToolRef(dto.getToolRef()))
                .argumentTemplate(jsonbMapper.mapArgumentTemplate(dto.getArgumentTemplate()))
                .valid(true)
                .validationWarnings("[]")
                .createdBy(createdBy)
                .build();
    }

    public void update(TestSuite entity, TestSuiteRequestDto dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setDatasetId(dto.getDatasetId());
        entity.setDisabledTestCaseIds(serializeDisabledIds(dto.getDisabledTestCaseIds()));
        entity.setDeploymentRef(jsonbMapper.map(dto.getDeploymentRef()));
        entity.setEndpointRef(jsonbMapper.map(dto.getEndpointRef()));
        entity.setResponseColumns(jsonbMapper.mapResponseColumns(dto.getResponseColumns()));
        entity.setRequestTemplate(jsonbMapper.map(dto.getRequestTemplate()));
        entity.setInputBindings(jsonbMapper.mapInputBindings(dto.getInputBindings()));
        entity.setMcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(dto.getMcpDeploymentRef()));
        entity.setToolRef(jsonbMapper.mapToolRef(dto.getToolRef()));
        entity.setArgumentTemplate(jsonbMapper.mapArgumentTemplate(dto.getArgumentTemplate()));
    }

    /**
     * Builds a new {@link TestSuite} entity for cloning, applying overrides from the request DTO.
     * Null fields in the DTO inherit from {@code source}. Suite-scoped DIAL file references in JSONB
     * fields ({@code inputBindings}, {@code requestTemplate}, {@code argumentTemplate}) are rewritten
     * from the source suite path to the new suite path so that the cloned suite points to the files
     * copied by {@code FileService.copyFilesBetweenSuites}. The clone inherits {@code datasetId}
     * (unless overridden via {@code dto.datasetId}) and {@code disabledTestCaseIds} from the source.
     * Test case rows are NOT cloned — they remain owned by the dataset; any file references inside
     * test-case data are left untouched by the suite-clone path.
     * isValid and validationWarnings are NOT set here — they are set by the synchronous validation step.
     */
    public TestSuite toCloneEntity(TestSuite source, TestSuiteCloneRequestDto dto, UUID newId, String createdBy) {
        String sourcePrefix = "@ef/suites/" + source.getId() + "/";
        String targetPrefix = "@ef/suites/" + newId + "/";

        SuiteType suiteType = source.getSuiteType();

        String deploymentRef =
                dto.getDeploymentRef() != null ? jsonbMapper.map(dto.getDeploymentRef()) : source.getDeploymentRef();
        String endpointRef =
                dto.getEndpointRef() != null ? jsonbMapper.map(dto.getEndpointRef()) : source.getEndpointRef();
        String responseColumns = dto.getResponseColumns() != null
                ? jsonbMapper.mapResponseColumns(dto.getResponseColumns())
                : source.getResponseColumns();
        String mcpDeploymentRef = dto.getMcpDeploymentRef() != null
                ? jsonbMapper.mapMcpDeploymentRef(dto.getMcpDeploymentRef())
                : source.getMcpDeploymentRef();
        String toolRef = dto.getToolRef() != null ? jsonbMapper.mapToolRef(dto.getToolRef()) : source.getToolRef();

        String inputBindings = dto.getInputBindings() != null
                ? jsonbMapper.mapInputBindings(dto.getInputBindings())
                : source.getInputBindings();
        if (inputBindings != null) {
            inputBindings = inputBindings.replace(sourcePrefix, targetPrefix);
        }

        String requestTemplate = dto.getRequestTemplate() != null
                ? jsonbMapper.map(dto.getRequestTemplate())
                : source.getRequestTemplate();
        if (requestTemplate != null) {
            requestTemplate = requestTemplate.replace(sourcePrefix, targetPrefix);
        }

        String argumentTemplate = dto.getArgumentTemplate() != null
                ? jsonbMapper.mapArgumentTemplate(dto.getArgumentTemplate())
                : source.getArgumentTemplate();
        if (argumentTemplate != null) {
            argumentTemplate = argumentTemplate.replace(sourcePrefix, targetPrefix);
        }

        UUID datasetId = dto.getDatasetId() != null ? dto.getDatasetId() : source.getDatasetId();

        return TestSuite.builder()
                .id(newId)
                .name(dto.getName())
                .description(dto.getDescription() != null ? dto.getDescription() : source.getDescription())
                .suiteType(suiteType)
                .datasetId(datasetId)
                .disabledTestCaseIds(source.getDisabledTestCaseIds())
                .deploymentRef(deploymentRef)
                .endpointRef(endpointRef)
                .responseColumns(responseColumns)
                .requestTemplate(requestTemplate)
                .inputBindings(inputBindings)
                .mcpDeploymentRef(mcpDeploymentRef)
                .toolRef(toolRef)
                .argumentTemplate(argumentTemplate)
                .version(0L)
                .createdBy(createdBy)
                .build();
    }

    /**
     * Converts a {@link TestSuite} entity back to a {@link TestSuiteRequestDto} for use in validation.
     * Reverse-maps all JSONB-backed fields through {@link JsonbMapper}, mirroring {@link #toDto(TestSuite)}.
     */
    public TestSuiteRequestDto toRequestDto(TestSuite entity) {
        if (entity == null) {
            return null;
        }
        SuiteType suiteType = entity.getSuiteType() != null ? entity.getSuiteType() : SuiteType.DEPLOYMENT;
        return TestSuiteRequestDto.builder()
                .name(entity.getName())
                .description(entity.getDescription())
                .suiteType(suiteType)
                .datasetId(entity.getDatasetId())
                .disabledTestCaseIds(deserializeDisabledIds(entity.getDisabledTestCaseIds()))
                .deploymentRef(jsonbMapper.map(entity.getDeploymentRef()))
                .endpointRef(jsonbMapper.mapEndpointContract(entity.getEndpointRef()))
                .responseColumns(
                        entity.getResponseColumns() != null
                                ? jsonbMapper.mapResponseColumns(entity.getResponseColumns())
                                : null)
                .requestTemplate(jsonbMapper.mapRequestTemplate(entity.getRequestTemplate()))
                .inputBindings(
                        entity.getInputBindings() != null
                                ? jsonbMapper.mapInputBindings(entity.getInputBindings())
                                : null)
                .mcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(entity.getMcpDeploymentRef()))
                .toolRef(jsonbMapper.mapToolRef(entity.getToolRef()))
                .argumentTemplate(jsonbMapper.mapArgumentTemplate(entity.getArgumentTemplate()))
                .build();
    }

    /**
     * Deserialises the JSONB-encoded array of stringified UUIDs into a typed list.
     * Returns null when the column is null/blank so callers can distinguish "not set" from "empty".
     * Returns an empty list (logged) on a malformed payload so a single corrupt row cannot brick the read path.
     */
    private List<UUID> deserializeDisabledIds(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<String> raw = objectMapper.readValue(json, STRING_LIST_TYPE);
            List<UUID> ids = new ArrayList<>(raw.size());
            for (String s : raw) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                try {
                    ids.add(UUID.fromString(s));
                } catch (IllegalArgumentException ex) {
                    log.warn("Skipping malformed UUID in disabledTestCaseIds: {}", s, ex);
                }
            }
            return ids;
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize disabledTestCaseIds JSON: {}", ex.getMessage(), ex);
            return List.of();
        }
    }

    /**
     * Serialises a typed list of UUIDs to a JSONB-ready JSON array of stringified UUIDs.
     * Returns {@code "[]"} for null/empty input so the DB column is always non-null
     * (matches the DEFAULT defined in the V1.22 migration).
     */
    private String serializeDisabledIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        try {
            List<String> raw = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                if (id != null) {
                    raw.add(id.toString());
                }
            }
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize disabledTestCaseIds", ex);
        }
    }
}
