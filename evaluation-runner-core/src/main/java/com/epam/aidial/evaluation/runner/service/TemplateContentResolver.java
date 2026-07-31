package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resolves {@code ${{var}}}/{@code ${{var|type}}}/{@code ${{var:default}}}/{@code ${{var|type:default}}}
 * placeholders in a template value (string, or a structural JSON object/array of strings) against
 * bindings and test case data.
 *
 * <p>Shared by {@link RequestResolver} (URL/query/headers/multipart/url-encoded resolution and the
 * legacy Map-content JSON body path) and reused as the source of the shared placeholder patterns for
 * {@link JsonataSourcePreprocessor}.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class TemplateContentResolver {

    /**
     * Matches {@code ${{var}}}, {@code ${{var:default}}}, {@code ${{var|type}}},
     * {@code ${{var|type:default}}}. Group 1: variable name. Group 2 (optional): type hint. Group 3
     * (optional): default value.
     */
    static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{\\{([^:|}]+)(?:\\|([^:}]+))?(?::([^}]*))?\\}\\}");

    /**
     * Matches a string that is exactly one placeholder with no surrounding text.
     * Uses [^}]+ which already covers type-hinted placeholders like ${{var|file}}.
     */
    static final Pattern FULL_VALUE_PATTERN = Pattern.compile("^\\$\\{\\{[^}]+\\}\\}$");

    private final TemplateVariableResolver templateVariableResolver;
    private final DialFileRefResolver dialFileRefResolver;

    /**
     * Resolves {@code ${{}}} placeholders embedded anywhere in a plain string, stringifying each
     * resolved value via {@link Object#toString()}.
     */
    public String resolveString(
            String value,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            String defaultValue = matcher.group(3); // group 3: default value (group 2 is now type hint)
            InputBindingDto binding = bindingByVar.get(varName);
            Object resolved = templateVariableResolver.resolveVariable(varName, defaultValue, binding, data, warnings);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved != null ? resolved.toString() : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Recursively resolves {@code ${{}}} placeholders in a structural value (String, Map, List, or
     * passthrough primitive). A full-value string placeholder (the entire string is exactly one
     * placeholder) preserves the resolved value's type instead of stringifying it, and a
     * {@code |file} type hint resolves a String result to DIAL ref format.
     */
    @SuppressWarnings("unchecked")
    public Object resolveObject(
            Object value,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        if (value instanceof String str) {
            // Full-value replacement: if string is exactly one placeholder, return typed value
            if (FULL_VALUE_PATTERN.matcher(str).matches()) {
                Matcher m = PLACEHOLDER_PATTERN.matcher(str);
                if (m.find()) {
                    String varName = m.group(1).trim();
                    String typeHint = m.group(2); // group 2: type hint
                    String defaultValue = m.group(3); // group 3: default value
                    InputBindingDto binding = bindingByVar.get(varName);
                    Object resolved =
                            templateVariableResolver.resolveVariable(varName, defaultValue, binding, data, warnings);
                    // Resolve FILE-typed placeholder to DIAL ref format for JSON/URL-encoded bodies
                    if (SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint)
                            && resolved instanceof String resolvedRef) {
                        return dialFileRefResolver.resolveToDialRef(resolvedRef);
                    }
                    return resolved;
                }
            }
            // String interpolation
            return resolveString(str, bindingByVar, data, warnings);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(
                        String.valueOf(entry.getKey()), resolveObject(entry.getValue(), bindingByVar, data, warnings));
            }
            return result;
        } else if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(resolveObject(item, bindingByVar, data, warnings));
            }
            return result;
        }
        return value;
    }

    /**
     * Recursively serializes a value to a JSON string, preserving explicit {@code null} entries at
     * every level (Map values, List elements).
     *
     * <p>The shared project {@code ObjectMapper} applies a global {@code NON_NULL} property-inclusion
     * rule, so a plain {@code objectMapper.writeValueAsString(map)} silently drops null-valued map
     * entries — which would corrupt a resolved value's intended "explicit null" (e.g. a frame binding
     * or a JSONata {@code $exists} check downstream). This builds the {@link JsonNode} tree by hand
     * instead (per AGENTS.md's {@code ObjectNode}/{@code putNull} guidance for JSONB null
     * preservation), so it is safe for callers that need a JSON-literal-null to survive serialization
     * regardless of the caller's own {@code ObjectMapper} configuration.
     *
     * <p>Shared by {@link RequestBodyEvaluator} (Map-content path) and {@link JsonataSourcePreprocessor}
     * (typed placeholder value serialization) — both need the same null-preserving behavior and inject
     * their own {@code ObjectMapper}, so this is a stateless static helper rather than an instance
     * method requiring a fourth injectable component.
     */
    static String serializeJsonPreservingNulls(ObjectMapper objectMapper, Object value) {
        return toJsonNode(objectMapper, value).toString();
    }

    private static JsonNode toJsonNode(ObjectMapper objectMapper, Object value) {
        if (value == null) {
            return objectMapper.nullNode();
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = objectMapper.createObjectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object entryValue = entry.getValue();
                if (entryValue == null) {
                    node.putNull(key);
                } else {
                    node.set(key, toJsonNode(objectMapper, entryValue));
                }
            }
            return node;
        }
        if (value instanceof List<?> list) {
            ArrayNode node = objectMapper.createArrayNode();
            for (Object item : list) {
                node.add(toJsonNode(objectMapper, item));
            }
            return node;
        }
        return objectMapper.convertValue(value, JsonNode.class);
    }
}
