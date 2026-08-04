package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves {@code ${{}}} placeholders embedded in a JSONata <em>source</em> string (as opposed to a
 * structural JSON object) via a lexeme-aware textual scanner, before the combined text is parsed and
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
 * <p>A placeholder span is located by matching {@link TemplateContentResolver#PLACEHOLDER_PATTERN}
 * anchored at the current scan position (not by a naive {@code indexOf("}}")} search) — its default-value
 * group is {@code [^}]*}, so per the documented contract a default value must not itself contain a
 * {@code }} character. A {@code ${{...}}} span at a position where the pattern does not match (e.g. a
 * default value containing a literal {@code }}) is not a recognized placeholder at all: the scanner falls
 * through to plain-text handling one character at a time, so the raw {@code ${{...}}} text — including
 * any embedded quotes — reaches the JSONata parser unresolved and is rejected there like any other
 * malformed source. This same
 * pattern-anchored matching is shared by {@link #preprocess} and {@link #neutralize} (via {@link #scan}),
 * so both agree on exactly which spans are placeholders — {@link #neutralize} no longer force-substitutes
 * a span that {@link #preprocess} would otherwise leave verbatim, which previously let a JSONata-invalid
 * placeholder-shaped span validate at write time (200) only to fail every run.
 *
 * <p>Beyond {@code ${{}}} placeholders, the scanner models exactly four other JSONata lexeme shapes, so a
 * quote character inside any of them cannot flip the scanner's string-literal quote-state and derail
 * later placeholder detection:
 * <ul>
 *   <li>{@code '...'} / {@code "..."} string literals (honoring backslash escapes) — the only shapes
 *       that can themselves carry a resolved placeholder value;</li>
 *   <li>{@code `...`} backtick-quoted field names — copied through verbatim to the next backtick (no
 *       escape mechanism, matching the JSONata tokenizer), or to end-of-source if unterminated;</li>
 *   <li>{@code /* ... *&#47;} block comments — recognized and skipped as a unit; a quote or
 *       placeholder-shaped span inside a comment is passed through untouched;</li>
 *   <li>{@code /pattern/flags} regex literals — recognized only in an operand-starting position (start
 *       of source, or the nearest non-whitespace character back is one of <code>( , = ! &lt; &gt; ~ &amp;
 *       ? : [</code>), mirroring the dashjoin JSONata tokenizer's prefix/infix disambiguation so
 *       {@code a / b} (division) is never misdetected as the start of a regex; bracket depth and
 *       backslash-escaping are tracked so an unescaped {@code /} inside {@code (...)}/{@code [...]}/
 *       {@code {...}} does not close the literal early.</li>
 * </ul>
 * An unterminated literal, comment, backtick name, or regex is copied through to end-of-source verbatim
 * rather than throwing — write-time JSONata validation is responsible for rejecting genuinely malformed
 * source. Value resolution priority is identical to {@link TemplateVariableResolver}
 * (constantValue &gt; data[dataField] &gt; template default &gt; null + REQUIRED warning); an
 * unresolved value serializes as JSON {@code null} for the full-value/bare positions (a valid JSONata
 * literal), and as an empty string for the embedded position (mirroring
 * {@link TemplateContentResolver#resolveString}'s unresolved fallback).
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class JsonataSourcePreprocessor {

    /**
     * Substitution strategy for the two placeholder shapes the scanner recognizes: a full-value/bare
     * placeholder (the entire {@code ${{...}}} span, quoted or not) and an embedded-in-literal run of
     * text that contains one or more placeholders alongside other characters.
     */
    private interface PlaceholderSubstitutor {
        String resolveFullOrBare(String placeholder);

        String resolveEmbedded(String rawInner, char quote);
    }

    /**
     * Neutralizes every placeholder into a fixed, harmless token instead of resolving it: JSON
     * {@code null} for a full-value/bare placeholder, and the empty string for an embedded one. Used by
     * {@link #neutralize(String)} — it never touches bindings, test-case data, or the DIAL file
     * resolver, so it is safe to run before any of those are available.
     *
     * <p>{@link #resolveFullOrBare} only neutralizes a span that itself matches
     * {@link TemplateContentResolver#PLACEHOLDER_PATTERN} — the same check {@link #preprocess}'s real
     * substitutor applies via {@link #resolveFullOrBarePlaceholder} — so a span that would NOT be
     * substituted at run time is passed through verbatim here too, keeping {@link #neutralize} and
     * {@link #preprocess} in agreement on exactly which spans are placeholders.
     */
    private static final PlaceholderSubstitutor NEUTRAL_SUBSTITUTOR = new PlaceholderSubstitutor() {
        @Override
        public String resolveFullOrBare(String placeholder) {
            return isRecognizedPlaceholder(placeholder) ? "null" : placeholder;
        }

        @Override
        public String resolveEmbedded(String rawInner, char quote) {
            StringBuilder sb = new StringBuilder();
            Matcher matcher = TemplateContentResolver.PLACEHOLDER_PATTERN.matcher(rawInner);
            int last = 0;
            while (matcher.find()) {
                sb.append(rawInner, last, matcher.start());
                last = matcher.end();
            }
            sb.append(rawInner, last, rawInner.length());
            return sb.toString();
        }
    };

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
        return scan(source, new PlaceholderSubstitutor() {
            @Override
            public String resolveFullOrBare(String placeholder) {
                return resolveFullOrBarePlaceholder(placeholder, bindingByVar, data, warnings);
            }

            @Override
            public String resolveEmbedded(String rawInner, char quote) {
                return replaceEmbeddedPlaceholders(rawInner, quote, bindingByVar, data, warnings);
            }
        });
    }

    /**
     * Neutralizes every {@code ${{}}} placeholder in a JSONata source string into a fixed, harmless
     * token — JSON {@code null} for a quoted-full-value or bare placeholder, and the empty string for
     * an embedded-in-literal placeholder — without resolving bindings or test-case data. The result is
     * a placeholder-free approximation of the shape the source takes after real substitution, good
     * enough for {@link JsonataEvaluationService#validateExpression} to parse write-time (before any
     * binding/data is available) without rejecting a bare placeholder that is only valid JSONata once
     * substituted.
     *
     * @param source the raw JSONata source text (may contain zero or more placeholders)
     * @return the source text with every placeholder neutralized
     */
    public String neutralize(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        return scan(source, NEUTRAL_SUBSTITUTOR);
    }

    /**
     * Scans {@code source} once, tracking lexeme state (string literals, backtick-quoted names, block
     * comments, regex literals, and {@code ${{}}} placeholder spans), and delegates every placeholder
     * occurrence to {@code substitutor}. Shared by {@link #preprocess} (real resolution) and
     * {@link #neutralize} (fixed neutral tokens) so both see identical lexeme/placeholder detection.
     */
    private String scan(String source, PlaceholderSubstitutor substitutor) {
        StringBuilder result = new StringBuilder();
        int length = source.length();
        int i = 0;
        while (i < length) {
            if (source.startsWith("/*", i)) {
                i = consumeComment(source, i, result);
                continue;
            }
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = consumeStringLiteral(source, i, c, result, substitutor);
            } else if (c == '`') {
                i = consumeBacktickName(source, i, result);
            } else if (c == '/' && isOperandStartPosition(source, i)) {
                i = consumeRegexLiteral(source, i, result);
            } else if (source.startsWith("${{", i)) {
                Matcher m = matchPlaceholderAt(source, i);
                if (m == null) {
                    // Not a placeholder per PLACEHOLDER_PATTERN's grammar (e.g. a default value containing
                    // a literal "}}") — fall through to plain-text handling one character at a time, so the
                    // raw "${{" text (and everything after it) reaches the JSONata parser unresolved.
                    result.append(c);
                    i++;
                } else {
                    String placeholder = source.substring(i, m.end());
                    result.append(substitutor.resolveFullOrBare(placeholder));
                    i = m.end();
                }
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    /**
     * Matches {@link TemplateContentResolver#PLACEHOLDER_PATTERN} anchored at position {@code i} (not
     * merely somewhere at-or-after it), returning the successful {@link Matcher} or {@code null} when the
     * text starting at {@code i} does not form a well-formed placeholder span.
     */
    private static Matcher matchPlaceholderAt(String source, int i) {
        Matcher m = TemplateContentResolver.PLACEHOLDER_PATTERN.matcher(source);
        m.region(i, source.length());
        return m.lookingAt() ? m : null;
    }

    /** Whether {@code text} itself is a well-formed {@code ${{}}} placeholder span. */
    private static boolean isRecognizedPlaceholder(String text) {
        return TemplateContentResolver.PLACEHOLDER_PATTERN.matcher(text).find();
    }

    /**
     * Skips a {@code /* ... *&#47;} block comment starting at {@code start} (the {@code /}), copying it
     * to {@code result} unchanged — including any quote character or placeholder-shaped text inside it,
     * neither of which is given any special meaning inside a comment. Returns the index immediately
     * following the closing {@code *&#47;}.
     */
    private static int consumeComment(String source, int start, StringBuilder result) {
        int length = source.length();
        int close = source.indexOf("*/", start + 2);
        if (close < 0) {
            // Unterminated comment — copy the remainder verbatim; write-time JSONata validation is
            // responsible for rejecting genuinely malformed source.
            result.append(source, start, length);
            return length;
        }
        result.append(source, start, close + 2);
        return close + 2;
    }

    /**
     * Copies a {@code `...`} backtick-quoted name starting at {@code start} (the opening backtick)
     * through to the next backtick unchanged — the JSONata tokenizer has no escape mechanism for
     * backtick-quoted names, so neither does this scan. Returns the index immediately following the
     * closing backtick, or end-of-source when unterminated (copied verbatim, same graceful handling as an
     * unterminated string literal/comment).
     */
    private static int consumeBacktickName(String source, int start, StringBuilder result) {
        int length = source.length();
        int close = source.indexOf('`', start + 1);
        if (close < 0) {
            result.append(source, start, length);
            return length;
        }
        result.append(source, start, close + 1);
        return close + 1;
    }

    /** Characters after which a following {@code /} may start a regex literal (an operand may start there). */
    private static final String REGEX_OPERAND_PRECEDING_CHARS = "(,=!<>~&?:[";

    /**
     * Whether position {@code i} in {@code source} is a valid position for a JSONata regex literal to
     * start — i.e. an operand may start there — mirroring the dashjoin JSONata tokenizer's prefix/infix
     * disambiguation of {@code /} between "start of a regex" and "division operator": true at start of
     * source, or when the nearest non-whitespace character before {@code i} is one of
     * {@code ( , = ! < > ~ & ? : [}. Anything else (a name, number, string, or closing bracket) means an
     * operand just ended, so {@code /} is division.
     */
    private static boolean isOperandStartPosition(String source, int i) {
        int j = i - 1;
        while (j >= 0 && Character.isWhitespace(source.charAt(j))) {
            j--;
        }
        return j < 0 || REGEX_OPERAND_PRECEDING_CHARS.indexOf(source.charAt(j)) >= 0;
    }

    /**
     * Consumes a {@code /pattern/flags} regex literal starting at {@code start} (the opening {@code /}),
     * copying it through unchanged — its content is never scanned for quotes or placeholders. Bracket
     * depth ({@code (`[`{}}) and backslash-escaping are tracked exactly like the dashjoin tokenizer's own
     * {@code scanRegex}, so an unescaped {@code /} nested inside a bracketed group does not close the
     * literal early. Returns the index immediately following the (optional {@code i}/{@code m}) flags, or
     * end-of-source when unterminated (copied verbatim, same graceful handling as an unterminated string
     * literal/comment/backtick name).
     */
    private static int consumeRegexLiteral(String source, int start, StringBuilder result) {
        int length = source.length();
        int i = start + 1;
        int depth = 0;
        while (i < length) {
            char c = source.charAt(i);
            boolean escaped = source.charAt(i - 1) == '\\';
            if (c == '/' && depth == 0 && !isEscapedSlash(source, i)) {
                int end = i + 1;
                while (end < length && (source.charAt(end) == 'i' || source.charAt(end) == 'm')) {
                    end++;
                }
                result.append(source, start, end);
                return end;
            }
            if (!escaped) {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                }
            }
            i++;
        }
        // Unterminated regex literal — copy the remainder verbatim; write-time JSONata validation is
        // responsible for rejecting genuinely malformed source.
        result.append(source, start, length);
        return length;
    }

    /** True when the {@code /} at {@code position} is preceded by an odd (escaping) run of backslashes. */
    private static boolean isEscapedSlash(String source, int position) {
        int backslashCount = 0;
        int j = position - 1;
        while (j >= 0 && source.charAt(j) == '\\') {
            backslashCount++;
            j--;
        }
        return backslashCount % 2 != 0;
    }

    /**
     * Consumes a full string literal starting at {@code start} (the opening quote), classifies it as
     * quoted-full-value / embedded / no-placeholder, appends the substituted (or unchanged) literal to
     * {@code result}, and returns the index immediately following the literal.
     */
    private int consumeStringLiteral(
            String source, int start, char quote, StringBuilder result, PlaceholderSubstitutor substitutor) {
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
            result.append(substitutor.resolveFullOrBare(inner));
        } else if (TemplateContentResolver.PLACEHOLDER_PATTERN.matcher(inner).find()) {
            result.append(quote);
            result.append(substitutor.resolveEmbedded(inner, quote));
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
