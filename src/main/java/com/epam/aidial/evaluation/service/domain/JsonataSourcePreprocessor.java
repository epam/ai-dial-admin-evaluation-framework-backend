package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves {@code ${{}}} placeholders embedded in a JSONata <em>source</em> string (as opposed to a
 * structural JSON object) via a quote-state textual scanner, before the combined text is parsed and
 * evaluated as JSONata.
 *
 * <p>Placeholders can appear in one of three positions, each substituted differently:
 * <ol>
 *   <li><b>Quoted-full-value</b> — the placeholder is the entire content of a string literal (e.g.
 *       {@code "${{var}}"}): the whole literal (quotes included) is replaced with the JSON
 *       serialization of the resolved typed value, so a bound array/object/number lands as real
 *       JSONata literal syntax rather than a re-quoted string.</li>
 *   <li><b>Embedded-in-literal</b> — the placeholder sits alongside other text inside a literal (e.g.
 *       {@code "Hello ${{name}}!"}): only the placeholder span is replaced, with the resolved value
 *       stringified and JSON-string-escaped for the enclosing quote so the literal stays intact.</li>
 *   <li><b>Bare</b> — the placeholder appears outside any string literal (e.g.
 *       {@code $append($history, ${{messages}})}): replaced with the JSON serialization of the typed
 *       value, same as quoted-full-value.</li>
 * </ol>
 *
 * <p>The scanner tracks whether the current position is inside a {@code '...'} or {@code "..."}
 * string literal (honoring backslash escapes); everything else — including backtick-quoted field
 * names and JSONata comments — is treated as plain code and passed through untouched. Value
 * resolution priority is identical to {@link TemplateVariableResolver}
 * (constantValue &gt; data[dataField] &gt; template default &gt; null + REQUIRED warning); an
 * unresolved value serializes as JSON {@code null} for the full-value/bare positions (a valid JSONata
 * literal), and as an empty string for the embedded position (mirroring
 * {@link TemplateContentResolver#resolveString}'s unresolved fallback).
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class JsonataSourcePreprocessor {

    private final TemplateVariableResolver templateVariableResolver;
    private final DialFileRefResolver dialFileRefResolver;
    private final ObjectMapper objectMapper;

    /**
     * Preprocesses {@code ${{}}} placeholders in a JSONata source string.
     *
     * @param source       the raw JSONata source text (may contain zero or more placeholders)
     * @param bindingByVar input bindings keyed by template variable name
     * @param data         test case data used to resolve data-field bindings
     * @param warnings     accumulator for resolution warnings
     * @return the source text with every placeholder substituted
     */
    public String preprocess(
            String source,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        if (source == null || source.isEmpty()) {
            return source;
        }

        StringBuilder result = new StringBuilder();
        int length = source.length();
        int i = 0;
        while (i < length) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = consumeStringLiteral(source, i, c, result, bindingByVar, data, warnings);
            } else if (source.startsWith("${{", i)) {
                int close = source.indexOf("}}", i + 3);
                if (close < 0) {
                    // Malformed placeholder (no closing "}}") — copy the remainder verbatim; write-time
                    // JSONata validation is responsible for rejecting genuinely malformed source.
                    result.append(source, i, length);
                    break;
                }
                String placeholder = source.substring(i, close + 2);
                result.append(resolveFullOrBarePlaceholder(placeholder, bindingByVar, data, warnings));
                i = close + 2;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    /**
     * Consumes a full string literal starting at {@code start} (the opening quote), classifies it as
     * quoted-full-value / embedded / no-placeholder, appends the substituted (or unchanged) literal to
     * {@code result}, and returns the index immediately following the literal.
     */
    private int consumeStringLiteral(
            String source,
            int start,
            char quote,
            StringBuilder result,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        int length = source.length();
        StringBuilder rawInner = new StringBuilder();
        int j = start + 1;
        boolean closed = false;
        while (j < length) {
            char cj = source.charAt(j);
            if (cj == '\\' && j + 1 < length) {
                rawInner.append(cj).append(source.charAt(j + 1));
                j += 2;
                continue;
            }
            if (cj == quote) {
                closed = true;
                j++;
                break;
            }
            rawInner.append(cj);
            j++;
        }

        if (!closed) {
            // Unterminated literal — copy the remainder verbatim; write-time JSONata validation is
            // responsible for rejecting genuinely malformed source.
            result.append(source, start, length);
            return length;
        }

        String inner = rawInner.toString();
        if (TemplateContentResolver.FULL_VALUE_PATTERN.matcher(inner).matches()) {
            result.append(resolveFullOrBarePlaceholder(inner, bindingByVar, data, warnings));
        } else if (TemplateContentResolver.PLACEHOLDER_PATTERN.matcher(inner).find()) {
            result.append(quote);
            result.append(replaceEmbeddedPlaceholders(inner, quote, bindingByVar, data, warnings));
            result.append(quote);
        } else {
            // No placeholder in this literal — copy it unchanged, escapes and all.
            result.append(source, start, j);
        }
        return j;
    }

    /**
     * Resolves a single full-value or bare placeholder (the entire {@code ${{...}}} span, with no
     * surrounding text) to the JSON serialization of its typed value.
     */
    private String resolveFullOrBarePlaceholder(
            String placeholder,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        Matcher m = TemplateContentResolver.PLACEHOLDER_PATTERN.matcher(placeholder);
        if (!m.find()) {
            return placeholder;
        }
        String varName = m.group(1).trim();
        String typeHint = m.group(2);
        String defaultValue = m.group(3);
        Object resolved = resolveTypedValue(varName, typeHint, defaultValue, bindingByVar, data, warnings);
        return TemplateContentResolver.serializeJsonPreservingNulls(objectMapper, resolved);
    }

    /**
     * Substitutes every {@code ${{}}} occurrence inside {@code inner} (the raw, still-escaped content
     * of a string literal) with its resolved value, stringified and escaped for inclusion inside a
     * literal delimited by {@code quote}. Text outside placeholder spans (including existing escape
     * sequences) is copied through unchanged.
     */
    private String replaceEmbeddedPlaceholders(
            String inner,
            char quote,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = TemplateContentResolver.PLACEHOLDER_PATTERN.matcher(inner);
        int last = 0;
        while (matcher.find()) {
            sb.append(inner, last, matcher.start());
            String varName = matcher.group(1).trim();
            String defaultValue = matcher.group(3);
            InputBindingDto binding = bindingByVar.get(varName);
            Object resolved = templateVariableResolver.resolveVariable(varName, defaultValue, binding, data, warnings);
            String stringified = resolved != null ? resolved.toString() : "";
            sb.append(escapeForLiteral(stringified, quote));
            last = matcher.end();
        }
        sb.append(inner, last, inner.length());
        return sb.toString();
    }

    /**
     * Resolves a variable's typed value, applying the {@code |file} type-hint DIAL-ref conversion —
     * identical to {@link TemplateContentResolver#resolveObject}'s full-value path.
     */
    private Object resolveTypedValue(
            String varName,
            String typeHint,
            String defaultValue,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        InputBindingDto binding = bindingByVar.get(varName);
        Object resolved = templateVariableResolver.resolveVariable(varName, defaultValue, binding, data, warnings);
        if (SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint) && resolved instanceof String resolvedRef) {
            return dialFileRefResolver.resolveToDialRef(resolvedRef);
        }
        return resolved;
    }

    /**
     * Escapes {@code raw} for safe inclusion inside a string literal delimited by {@code quote}:
     * backslash, the active quote char, and control characters.
     */
    private static String escapeForLiteral(String raw, char quote) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int idx = 0; idx < raw.length(); idx++) {
            char ch = raw.charAt(idx);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (ch == quote) {
                        sb.append('\\').append(ch);
                    } else if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
