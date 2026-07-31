package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class EndpointSchemaExtractor {

    public List<FieldDefinitionDto> extractParameterFields(EndpointContractDto endpoint) {
        if (endpoint == null) {
            return List.of();
        }

        List<FieldDefinitionDto> fields = new ArrayList<>();
        List<ParameterDefinitionDto> parameters = endpoint.getParameters();
        if (parameters != null) {
            for (ParameterDefinitionDto param : parameters) {
                if (param == null) {
                    continue;
                }
                fields.add(convertParameterToSchemaField(param));
            }
        }

        RequestBodySchemaDto bodySchema = endpoint.getRequestBodySchema();
        if (bodySchema instanceof JsonRequestBodySchemaDto jsonSchema && jsonSchema.getSchema() != null) {
            fields.addAll(flattenTopLevelProperties(jsonSchema.getSchema()));
        }

        return List.copyOf(fields);
    }

    private static FieldDefinitionDto convertParameterToSchemaField(ParameterDefinitionDto param) {
        return FieldDefinitionDto.builder()
                .name(param.getName())
                .type(inferTypeFromJsonSchema(param.getSchema()))
                .required(param.isRequired())
                .build();
    }

    private static List<FieldDefinitionDto> flattenTopLevelProperties(Map<String, Object> schema) {
        Map<String, Object> properties = asMap(schema.get("properties"));
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }

        Set<String> requiredFields =
                asStringList(schema.get("required")).stream().collect(Collectors.toSet());

        List<FieldDefinitionDto> result = new ArrayList<>(properties.size());
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            SchemaFieldType type = inferTypeFromJsonSchema(asMap(entry.getValue()));
            boolean required = requiredFields.contains(name);
            result.add(FieldDefinitionDto.builder()
                    .name(name)
                    .type(type)
                    .required(required)
                    .build());
        }
        return result;
    }

    private static SchemaFieldType inferTypeFromJsonSchema(Map<String, Object> schema) {
        if (schema == null) {
            return SchemaFieldType.STRING;
        }

        Object type = schema.get("type");
        SchemaFieldType mapped = mapType(type);
        if (mapped != null) {
            return mapped;
        }
        if (schema.containsKey("properties")) {
            return SchemaFieldType.OBJECT;
        }
        if (schema.containsKey("items")) {
            return SchemaFieldType.ARRAY;
        }
        return SchemaFieldType.STRING;
    }

    private static SchemaFieldType mapType(Object type) {
        if (type instanceof String typeName) {
            return mapTypeName(typeName);
        }
        if (type instanceof List<?> typeList) {
            for (Object entry : typeList) {
                if (entry instanceof String name) {
                    SchemaFieldType mapped = mapTypeName(name);
                    if (mapped != null) {
                        return mapped;
                    }
                }
            }
        }
        return null;
    }

    private static SchemaFieldType mapTypeName(String typeName) {
        String normalized = typeName.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STRING" -> SchemaFieldType.STRING;
            case "INTEGER" -> SchemaFieldType.INTEGER;
            case "NUMBER" -> SchemaFieldType.NUMBER;
            case "BOOLEAN" -> SchemaFieldType.BOOLEAN;
            case "OBJECT" -> SchemaFieldType.OBJECT;
            case "ARRAY" -> SchemaFieldType.ARRAY;
            default -> null;
        };
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object entry : rawList) {
            if (entry instanceof String str) {
                result.add(str);
            }
        }
        return result;
    }
}
