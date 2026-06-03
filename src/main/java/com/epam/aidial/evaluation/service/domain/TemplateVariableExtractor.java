package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.FormPartDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableSource;
import com.epam.aidial.evaluation.service.domain.dto.UrlEncodedFormRequestBodyDto;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * Parses {@code ${{variable}}} and {@code ${{variable:default}}} placeholders from
 * all fields of a {@link RequestTemplateDto}.
 */
@Component
public class TemplateVariableExtractor {

    /**
     * Matches ${{varName}}, ${{varName:default}}, ${{varName|type}}, ${{varName|type:default}}.
     * Group 1: variable name (no colon, pipe, or closing brace).
     * Group 2 (optional): type hint keyword (after |, before : or }}).
     * Group 3 (optional): default value (everything after the first colon until }}).
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$\\{\\{([^:|}]+)(?:\\|([^:}]+))?(?::([^}]*))?\\}\\}");

    /**
     * Extracts all template variables from the given RequestTemplateDto.
     * Variables appearing in multiple sections are tracked once with multiple sources.
     *
     * @param template the request template (may be null)
     * @return list of extracted variables in discovery order (deterministic)
     */
    public List<ExtractedVariable> extract(RequestTemplateDto template) {
        return extractWithWarnings(template).getVariables();
    }

    /**
     * Checks whether the given value is a full-value template variable placeholder.
     * Returns {@code true} when the entire string matches the {@code ${{...}}} pattern
     * (uses {@link Matcher#matches()}, not {@link Matcher#find()}).
     *
     * @param value the string to check (may be null)
     * @return {@code true} if the value is a full-value placeholder
     */
    public boolean isPlaceholder(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return PLACEHOLDER_PATTERN.matcher(value).matches();
    }

    /**
     * Extracts all template variables from the given ArgumentTemplateDto (MCP suites).
     * Variables are tagged with {@link TemplateVariableSource#ARGUMENT}.
     *
     * @param argumentTemplate the MCP argument template (may be null)
     * @return list of extracted variables in discovery order
     */
    public List<ExtractedVariable> extractFromArgumentTemplate(ArgumentTemplateDto argumentTemplate) {
        return extractFromArgumentTemplateWithWarnings(argumentTemplate).getVariables();
    }

    /**
     * Extracts all template variables and collects warnings for unrecognised type hints
     * from the given ArgumentTemplateDto (MCP suites).
     *
     * @param argumentTemplate the MCP argument template (may be null)
     * @return extraction result with variables and warnings
     */
    public ExtractionResult extractFromArgumentTemplateWithWarnings(ArgumentTemplateDto argumentTemplate) {
        if (argumentTemplate == null || argumentTemplate.getArguments() == null) {
            return new ExtractionResult(List.of(), List.of());
        }
        Map<String, ExtractedVariable> variables = new LinkedHashMap<>();
        List<String> typeHintWarnings = new ArrayList<>();
        extractFromObject(
                argumentTemplate.getArguments(), TemplateVariableSource.ARGUMENT, variables, typeHintWarnings);
        return new ExtractionResult(new ArrayList<>(variables.values()), typeHintWarnings);
    }

    /**
     * Extracts all template variables and collects warnings for unrecognised type hints.
     *
     * @param template the request template (may be null)
     * @return extraction result with variables and warnings
     */
    public ExtractionResult extractWithWarnings(RequestTemplateDto template) {
        if (template == null) {
            return new ExtractionResult(List.of(), List.of());
        }

        // Use LinkedHashMap for deterministic ordering
        Map<String, ExtractedVariable> variables = new LinkedHashMap<>();
        List<String> typeHintWarnings = new ArrayList<>();

        // URL
        if (template.getUrlTemplate() != null) {
            extractFromString(template.getUrlTemplate(), TemplateVariableSource.URL, variables, typeHintWarnings);
        }

        // Query params
        if (template.getQueryParams() != null) {
            for (KeyValueTemplateDto kv : template.getQueryParams()) {
                if (kv != null && kv.getValue() != null) {
                    extractFromString(kv.getValue(), TemplateVariableSource.QUERY, variables, typeHintWarnings);
                }
            }
        }

        // Headers
        if (template.getHeaders() != null) {
            for (KeyValueTemplateDto kv : template.getHeaders()) {
                if (kv != null && kv.getValue() != null) {
                    extractFromString(kv.getValue(), TemplateVariableSource.HEADER, variables, typeHintWarnings);
                }
            }
        }

        // Body — content-type aware extraction
        if (template.getBody() != null) {
            extractFromBody(template.getBody(), variables, typeHintWarnings);
        }

        return new ExtractionResult(new ArrayList<>(variables.values()), typeHintWarnings);
    }

    private void extractFromBody(
            RequestBodyDto body, Map<String, ExtractedVariable> variables, List<String> typeHintWarnings) {
        if (body instanceof JsonRequestBodyDto jsonBody) {
            if (jsonBody.getContent() != null) {
                extractFromObject(jsonBody.getContent(), TemplateVariableSource.BODY, variables, typeHintWarnings);
            }
        } else if (body instanceof MultipartFormDataRequestBodyDto multipartBody) {
            if (multipartBody.getContent() != null) {
                for (FormPartDto part : multipartBody.getContent()) {
                    if (part == null) {
                        continue;
                    }
                    if (part.getValue() != null) {
                        extractFromObject(part.getValue(), TemplateVariableSource.BODY, variables, typeHintWarnings);
                    }
                    if (part.getFilename() != null) {
                        extractFromString(part.getFilename(), TemplateVariableSource.BODY, variables, typeHintWarnings);
                    }
                }
            }
        } else if (body instanceof UrlEncodedFormRequestBodyDto urlEncodedBody) {
            if (urlEncodedBody.getContent() != null) {
                for (KeyValueTemplateDto kv : urlEncodedBody.getContent()) {
                    if (kv != null && kv.getValue() != null) {
                        extractFromString(kv.getValue(), TemplateVariableSource.BODY, variables, typeHintWarnings);
                    }
                }
            }
        }
    }

    private void extractFromString(
            String value,
            TemplateVariableSource source,
            Map<String, ExtractedVariable> variables,
            List<String> typeHintWarnings) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            String typeHintToken = matcher.group(2); // null if no |type
            String defaultValue = matcher.group(3); // null if no :default
            if (varName.isEmpty()) {
                continue;
            }

            SchemaFieldType declaredType = parseTypeHint(typeHintToken, varName, typeHintWarnings);

            ExtractedVariable existing = variables.get(varName);
            if (existing != null) {
                existing.getSources().add(source);
                // First-seen declaredType and default win; do not overwrite
            } else {
                variables.put(
                        varName,
                        new ExtractedVariable(
                                varName, EnumSet.of(source), defaultValue != null, defaultValue, declaredType));
            }
        }
    }

    private static SchemaFieldType parseTypeHint(String typeHintToken, String varName, List<String> typeHintWarnings) {
        if (typeHintToken == null) {
            return null;
        }
        String trimmed = typeHintToken.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return SchemaFieldType.valueOf(trimmed.toUpperCase());
        } catch (IllegalArgumentException e) {
            typeHintWarnings.add("Unrecognised type hint '" + trimmed + "' on variable '" + varName + "'");
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void extractFromObject(
            Object value,
            TemplateVariableSource source,
            Map<String, ExtractedVariable> variables,
            List<String> typeHintWarnings) {
        if (value instanceof String str) {
            extractFromString(str, source, variables, typeHintWarnings);
        } else if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                extractFromObject(v, source, variables, typeHintWarnings);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                extractFromObject(item, source, variables, typeHintWarnings);
            }
        }
        // primitives (numbers, booleans, null) are ignored
    }

    /**
     * Represents a template variable extracted from a RequestTemplateDto.
     */
    @Data
    @AllArgsConstructor
    public static class ExtractedVariable {
        private String name;
        private Set<TemplateVariableSource> sources;
        private boolean hasDefault;
        private String defaultValue;
        private SchemaFieldType declaredType;
    }

    /**
     * Result of template variable extraction, including any type-hint warnings.
     */
    @Getter
    @AllArgsConstructor
    public static class ExtractionResult {
        private final List<ExtractedVariable> variables;
        private final List<String> typeHintWarnings;
    }
}
