package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FormPartSchemaDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.UrlEncodedFormRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates data against JSON Schema (Draft-07).
 * Compiles schema once per Dataset and caches compiled schemas; invalidate when the dataset's
 * {@code testCaseSchema} changes (i.e., on {@code DatasetService.update} when schema differs and on
 * {@code DatasetService.delete}). Suites do NOT own a schema; multiple suites may share a dataset,
 * so the cache key MUST be datasetId — never suiteId — or invalidations would miss the live entry.
 * Warnings are truncated to validation.max-warnings-per-case.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class SchemaValidationService {

    private final ObjectMapper objectMapper;
    private final ValidationProperties validationProperties;

    private static final SchemaRegistry SCHEMA_FACTORY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);

    private static final String META_SCHEMA_RESOURCE = "schemas/json-schema-draft-07.json";

    /**
     * All valid JSON Schema Draft-07 keywords.
     * Unknown keywords are rejected to catch typos like "properties2".
     */
    private static final Set<String> VALID_DRAFT07_KEYWORDS = Set.of(
            // Core
            "$id",
            "$schema",
            "$ref",
            "$comment",
            "definitions",
            // Metadata
            "title",
            "description",
            "default",
            "readOnly",
            "writeOnly",
            "examples",
            // Validation - any type
            "type",
            "enum",
            "const",
            // Validation - numeric
            "multipleOf",
            "maximum",
            "exclusiveMaximum",
            "minimum",
            "exclusiveMinimum",
            // Validation - strings
            "maxLength",
            "minLength",
            "pattern",
            "format",
            "contentEncoding",
            "contentMediaType",
            // Validation - arrays
            "items",
            "additionalItems",
            "maxItems",
            "minItems",
            "uniqueItems",
            "contains",
            // Validation - objects
            "maxProperties",
            "minProperties",
            "required",
            "properties",
            "patternProperties",
            "additionalProperties",
            "dependencies",
            "propertyNames",
            // Conditional
            "if",
            "then",
            "else",
            // Composition
            "allOf",
            "anyOf",
            "oneOf",
            "not");

    private static final int CACHE_MAX_SIZE = 10_000;
    private static final int CACHE_EXPIRE_HOURS = 24;

    private final Cache<String, Schema> schemaCache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_SIZE)
            .expireAfterWrite(CACHE_EXPIRE_HOURS, TimeUnit.HOURS)
            .build();

    private Schema metaSchema;

    @PostConstruct
    void loadMetaSchema() {
        try (InputStream is = new ClassPathResource(META_SCHEMA_RESOURCE).getInputStream()) {
            JsonNode metaSchemaNode = objectMapper.readTree(is);
            metaSchema = SCHEMA_FACTORY.getSchema(metaSchemaNode);
            log.info("Loaded JSON Schema Draft-07 meta-schema from classpath: {}", META_SCHEMA_RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load meta-schema from " + META_SCHEMA_RESOURCE, e);
        }
    }

    /**
     * Validates data against the given JSON Schema (as Map).
     */
    public ValidationResult validate(Map<String, Object> data, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return ValidationResult.builder().valid(true).warnings(List.of()).build();
        }
        if (data == null) {
            data = Map.of();
        }

        JsonNode schemaNode = objectMapper.valueToTree(schema);
        JsonNode dataNode = objectMapper.valueToTree(data);

        Schema jsonSchema = SCHEMA_FACTORY.getSchema(schemaNode);
        List<Error> messages = jsonSchema.validate(dataNode);

        return toResult(messages);
    }

    /**
     * Validates data using a cached compiled schema for the given Dataset and part.
     */
    public ValidationResult validateWithCache(
            UUID datasetId, String part, Map<String, Object> schema, Map<String, Object> data) {
        if (schema == null || schema.isEmpty()) {
            return ValidationResult.builder().valid(true).warnings(List.of()).build();
        }
        if (data == null) {
            data = Map.of();
        }

        String cacheKey = cacheKey(datasetId, part);
        Schema jsonSchema = schemaCache.get(cacheKey, k -> {
            JsonNode schemaNode = objectMapper.valueToTree(schema);
            return SCHEMA_FACTORY.getSchema(schemaNode);
        });

        JsonNode dataNode = objectMapper.valueToTree(data);
        List<Error> messages = jsonSchema.validate(dataNode);
        return toResult(messages);
    }

    /**
     * Validates a polymorphic RequestBodySchemaDto.
     * Delegates to the appropriate validation based on content type.
     */
    public Optional<String> getSchemaValidationError(RequestBodySchemaDto schemaDto) {
        if (schemaDto == null) {
            return Optional.empty();
        }
        if (schemaDto instanceof JsonRequestBodySchemaDto jsonSchema) {
            return getSchemaValidationError(jsonSchema.getSchema());
        } else if (schemaDto instanceof MultipartFormDataRequestBodySchemaDto multipartSchema) {
            return validateMultipartSchema(multipartSchema);
        } else if (schemaDto instanceof UrlEncodedFormRequestBodySchemaDto urlEncodedSchema) {
            return getSchemaValidationError(urlEncodedSchema.getSchema());
        }
        return Optional.of(
                "Unknown request body schema type: " + schemaDto.getClass().getSimpleName());
    }

    /**
     * Checks if the given Map is a valid JSON Schema (Draft-07).
     * Rejects schemas containing {@code $ref} or unknown keywords; validates against bundled meta-schema.
     */
    public Optional<String> getSchemaValidationError(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> refError = checkNoRef(schema);
        if (refError.isPresent()) {
            return refError;
        }
        Optional<String> unknownKeywordError = checkNoUnknownKeywords(schema, "$");
        if (unknownKeywordError.isPresent()) {
            return unknownKeywordError;
        }
        Optional<String> metaError = validateAgainstMetaSchema(schema);
        if (metaError.isPresent()) {
            return metaError;
        }
        try {
            JsonNode schemaNode = objectMapper.valueToTree(schema);
            SCHEMA_FACTORY.getSchema(schemaNode);
            return Optional.empty();
        } catch (Exception e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return Optional.of(message);
        }
    }

    /**
     * Builds a JSON Schema (Draft-07) object from field definitions.
     */
    public static Map<String, Object> buildFieldSchema(List<FieldDefinitionDto> fields) {
        if (fields == null || fields.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (FieldDefinitionDto f : fields) {
            if (f == null || f.getName() == null || f.getName().isBlank()) {
                continue;
            }
            properties.put(f.getName(), Map.of("type", jsonSchemaType(f.getType())));
            if (f.isRequired()) {
                required.add(f.getName());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /**
     * @deprecated Use {@link #buildFieldSchema(List)} instead.
     */
    @Deprecated(forRemoval = true)
    public static Map<String, Object> buildFactsSchema(List<FieldDefinitionDto> factFields) {
        return buildFieldSchema(factFields);
    }

    private Optional<String> validateMultipartSchema(MultipartFormDataRequestBodySchemaDto schema) {
        if (schema.getParts() == null || schema.getParts().isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < schema.getParts().size(); i++) {
            FormPartSchemaDto part = schema.getParts().get(i);
            if (part.getName() == null || part.getName().isBlank()) {
                return Optional.of("parts[" + i + "]: name must not be blank");
            }
            if (part.getSchema() != null && !part.getSchema().isEmpty()) {
                Optional<String> err = getSchemaValidationError(part.getSchema());
                if (err.isPresent()) {
                    return Optional.of("parts[" + i + "].schema: " + err.get());
                }
            }
        }
        return Optional.empty();
    }

    private static String jsonSchemaType(SchemaFieldType type) {
        if (type == null) {
            return "string";
        }
        return switch (type) {
            case STRING, FILE -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case OBJECT -> "object";
            case ARRAY -> "array";
        };
    }

    /**
     * Invalidates all cached compiled schemas for the given Dataset.
     */
    public void invalidateSchemaCache(UUID datasetId) {
        String prefix = datasetId.toString() + ":";
        schemaCache.asMap().keySet().removeIf(k -> k != null && k.startsWith(prefix));
    }

    private static String cacheKey(UUID datasetId, String part) {
        return datasetId.toString() + ":" + part;
    }

    private ValidationResult toResult(List<Error> messages) {
        List<ValidationWarningDto> warningDtos = messages.stream()
                .map(SchemaValidationService::toValidationWarningDto)
                .collect(Collectors.toCollection(ArrayList::new));

        int maxWarnings = validationProperties.getMaxWarningsPerCase();
        if (warningDtos.size() > maxWarnings) {
            warningDtos.subList(maxWarnings, warningDtos.size()).clear();
        }

        return ValidationResult.builder()
                .valid(messages.isEmpty())
                .warnings(List.copyOf(warningDtos))
                .build();
    }

    private static ValidationWarningDto toValidationWarningDto(Error m) {
        String path = m.getInstanceLocation() != null ? m.getInstanceLocation().toString() : "$";
        if (path.isBlank()) {
            path = "$";
        }
        return ValidationWarningDto.builder()
                .fieldName(propertyFromPath(path))
                .path(path)
                .message(m.getMessage())
                .code(typeToCode(m.getKeyword()))
                .build();
    }

    static String propertyFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.startsWith("/")) {
            return propertyFromJsonPointer(path);
        }
        return propertyFromJsonPath(path);
    }

    private static String propertyFromJsonPointer(String pointer) {
        if ("/".equals(pointer)) {
            return null;
        }
        String[] segments = pointer.substring(1).split("/");
        if (segments.length == 0) {
            return null;
        }
        String leaf = segments[segments.length - 1];
        return isNumeric(leaf) ? null : leaf;
    }

    private static String propertyFromJsonPath(String jsonPath) {
        if ("$".equals(jsonPath) || "#".equals(jsonPath)) {
            return null;
        }
        if (jsonPath.matches("^\\$\\[\\d+\\]$")) {
            return null;
        }
        String path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
        if (path.isEmpty()) {
            return null;
        }
        String withoutArrays = path.replaceAll("\\[\\d+\\]", "");
        int lastDot = withoutArrays.lastIndexOf('.');
        String leaf = lastDot >= 0 ? withoutArrays.substring(lastDot + 1) : withoutArrays;
        return leaf.isEmpty() ? null : leaf;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static ValidationWarningCode typeToCode(String type) {
        if (type == null || type.isBlank()) {
            return ValidationWarningCode.UNKNOWN;
        }
        return switch (type) {
            case "required" -> ValidationWarningCode.REQUIRED;
            case "type" -> ValidationWarningCode.TYPE;
            case "format" -> ValidationWarningCode.FORMAT;
            case "pattern" -> ValidationWarningCode.PATTERN;
            case "enum" -> ValidationWarningCode.ENUM;
            case "additionalProperties" -> ValidationWarningCode.ADDITIONAL;
            default -> ValidationWarningCode.UNKNOWN;
        };
    }

    private Optional<String> checkNoRef(Map<String, Object> schema) {
        if (schema.containsKey("$ref")) {
            return Optional.of("$ref not supported in v1");
        }
        for (Object value : schema.values()) {
            Optional<String> nested = checkNoRefInValue(value);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> checkNoRefInValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return checkNoRef((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Optional<String> nested = checkNoRefInValue(item);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> checkNoUnknownKeywords(Map<String, Object> schema, String path) {
        for (String key : schema.keySet()) {
            if (!VALID_DRAFT07_KEYWORDS.contains(key)) {
                return Optional.of("Unknown keyword '" + key + "' at " + path
                        + ". Valid keywords: type, properties, required, items, etc.");
            }
        }
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Optional<String> nested = checkUnknownKeywordsInValue(value, path + "." + key);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> checkUnknownKeywordsInValue(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            if (isSchemaContext(path)) {
                return checkNoUnknownKeywords((Map<String, Object>) map, path);
            }
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Optional<String> nested = checkUnknownKeywordsInValue(list.get(i), path + "[" + i + "]");
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private boolean isSchemaContext(String path) {
        if (path.endsWith(".items")
                || path.endsWith(".additionalItems")
                || path.endsWith(".additionalProperties")
                || path.endsWith(".propertyNames")
                || path.endsWith(".contains")
                || path.endsWith(".not")
                || path.endsWith(".if")
                || path.endsWith(".then")
                || path.endsWith(".else")) {
            return true;
        }
        if (path.matches(".*\\.(allOf|anyOf|oneOf|items)\\[\\d+\\]$")) {
            return true;
        }
        if (path.matches(".*\\.(properties|patternProperties|definitions|dependencies)\\.[^.]+$")) {
            return true;
        }
        return false;
    }

    private Optional<String> validateAgainstMetaSchema(Map<String, Object> schema) {
        JsonNode schemaNode = objectMapper.valueToTree(schema);
        List<Error> errors = metaSchema.validate(schemaNode);
        if (errors != null && !errors.isEmpty()) {
            return Optional.of(errors.iterator().next().getMessage());
        }
        return Optional.empty();
    }
}
