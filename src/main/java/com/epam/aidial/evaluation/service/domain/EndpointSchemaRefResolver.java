package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.UrlEncodedFormRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class EndpointSchemaRefResolver {

    public EndpointContractDto resolve(EndpointContractDto endpoint) {
        if (endpoint == null) {
            return null;
        }

        return EndpointContractDto.builder()
                .method(endpoint.getMethod())
                .relativeUrlPattern(endpoint.getRelativeUrlPattern())
                .operationId(endpoint.getOperationId())
                .parameters(resolveParameters(endpoint.getParameters()))
                .requestBodySchema(resolveRequestBodySchema(endpoint.getRequestBodySchema()))
                .responseBodySchema(resolveSchema(endpoint.getResponseBodySchema()))
                .build();
    }

    private List<ParameterDefinitionDto> resolveParameters(List<ParameterDefinitionDto> parameters) {
        if (parameters == null) {
            return null;
        }
        List<ParameterDefinitionDto> resolved = new ArrayList<>();
        for (ParameterDefinitionDto parameter : parameters) {
            if (parameter == null) {
                continue;
            }
            resolved.add(ParameterDefinitionDto.builder()
                    .name(parameter.getName())
                    .in(parameter.getIn())
                    .required(parameter.isRequired())
                    .schema(resolveSchema(parameter.getSchema()))
                    .build());
        }
        return resolved;
    }

    private RequestBodySchemaDto resolveRequestBodySchema(RequestBodySchemaDto schemaDto) {
        if (schemaDto == null) {
            return null;
        }
        if (schemaDto instanceof JsonRequestBodySchemaDto jsonSchema) {
            return JsonRequestBodySchemaDto.builder()
                    .schema(resolveSchema(jsonSchema.getSchema()))
                    .build();
        } else if (schemaDto instanceof UrlEncodedFormRequestBodySchemaDto urlSchema) {
            return UrlEncodedFormRequestBodySchemaDto.builder()
                    .schema(resolveSchema(urlSchema.getSchema()))
                    .build();
        }
        // Multipart schemas don't contain $ref — pass through
        return schemaDto;
    }

    public Map<String, Object> resolveSchema(Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }
        Object resolved = resolveNode(schema, schema, new HashSet<>());
        if (resolved instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    result.put(stringKey, value);
                }
            });
            return result;
        }
        return schema;
    }

    private Object resolveNode(Object node, Map<String, Object> root, Set<String> refStack) {
        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    map.put(stringKey, value);
                }
            });

            if (map.containsKey("$ref")) {
                Object refValue = map.get("$ref");
                if (!(refValue instanceof String ref)) {
                    throw new ValidationException("Invalid $ref value");
                }
                Object resolved = resolveRef(ref, root, refStack);
                if (resolved instanceof Map<?, ?> resolvedMap) {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    resolvedMap.forEach((key, value) -> {
                        if (key instanceof String stringKey) {
                            merged.put(stringKey, value);
                        }
                    });
                    map.forEach((key, value) -> {
                        if (!"$ref".equals(key)) {
                            merged.put(key, value);
                        }
                    });
                    return resolveNode(merged, root, refStack);
                }
                return resolved;
            }

            Map<String, Object> resolvedMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                resolvedMap.put(entry.getKey(), resolveNode(entry.getValue(), root, refStack));
            }
            return resolvedMap;
        }

        if (node instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object entry : list) {
                resolved.add(resolveNode(entry, root, refStack));
            }
            return resolved;
        }

        return node;
    }

    private Object resolveRef(String ref, Map<String, Object> root, Set<String> refStack) {
        if (!ref.startsWith("#/")) {
            throw new ValidationException("Unsupported $ref: " + ref);
        }
        if (!refStack.add(ref)) {
            throw new ValidationException("Circular $ref detected: " + ref);
        }

        String[] parts = ref.substring(2).split("/");
        Object current = root;
        for (String rawPart : parts) {
            String part = unescapePointer(rawPart);
            if (!(current instanceof Map<?, ?> map)) {
                throw new ValidationException("Unresolvable $ref: " + ref);
            }
            Object next = map.get(part);
            if (next == null) {
                throw new ValidationException("Unresolvable $ref: " + ref);
            }
            current = next;
        }

        refStack.remove(ref);
        return resolveNode(current, root, refStack);
    }

    private static String unescapePointer(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }
}
