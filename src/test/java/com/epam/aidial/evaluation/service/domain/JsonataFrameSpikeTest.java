package com.epam.aidial.evaluation.service.domain;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.Jsonata.Frame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Spike: validates the {@code com.dashjoin.jsonata.Jsonata.Frame} API (unused prior to this
 * change) against the behaviors the request-template / response-column extraction rework
 * depends on: unbound vs. null-bound frame variables, {@code $append} undefined/null semantics,
 * {@link Frame#bind} for heterogeneous value types, {@link Frame#setRuntimeBounds}, JSON
 * round-trip fidelity through JSONata evaluation (pins flag F1), and placeholder-injection
 * precursor splicing. Results feed design.md decisions for the
 * {@code jsonata-request-templates} change.
 */
@DisplayName("JSONata Frame spike (dashjoin:jsonata:0.9.10)")
class JsonataFrameSpikeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("unbound frame variable: $history with an empty frame evaluates to undefined (Java null)")
    void evaluate_unboundFrameVariable_returnsUndefined() throws Exception {
        Jsonata expr = jsonata("$history");
        Frame frame = expr.createFrame();

        Object result = expr.evaluate(new LinkedHashMap<>(), frame);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName(
            "$append($history, [...]) with $history UNBOUND returns just the new array (undefined-append semantics)")
    void evaluate_appendWithUnboundHistory_returnsOnlyNewArray() throws Exception {
        Jsonata expr = jsonata("$append($history, [{\"role\":\"user\"}])");
        Frame frame = expr.createFrame();

        Object result = expr.evaluate(new LinkedHashMap<>(), frame);

        assertThat(result).isEqualTo(List.of(Map.of("role", "user")));
    }

    @Test
    @DisplayName(
            "$append($history, [1]) with $history bound to Java null is indistinguishable from unbound (returns [1], NOT [null, 1])")
    void evaluate_appendWithJavaNullBoundHistory_behavesAsUndefined() throws Exception {
        // Pinned finding: Frame.bind(name, (Object) null) is NOT the same as binding a JSON null.
        // Frame#lookup returns Java null for both a bound-null key and a genuinely unbound key, so
        // $append's `arg1 == null` undefined-check fires identically in both cases. To get real
        // null-append semantics you must bind the library's explicit-null sentinel instead (see the
        // next test) — plain Java null is NOT a way to express "this turn's history is JSON null".
        Jsonata expr = jsonata("$append($history, [1])");
        Frame frame = expr.createFrame();
        frame.bind("history", (Object) null);

        Object result = expr.evaluate(new LinkedHashMap<>(), frame);

        assertThat(result).isEqualTo(List.of(1));
    }

    @Test
    @DisplayName(
            "$append($history, [1]) with $history bound to the Jsonata NULL_VALUE sentinel returns [null, 1] (null-append semantics)")
    void evaluate_appendWithNullValueSentinelBoundHistory_prependsNull() throws Exception {
        // The library's explicit-null marker (Jsonata.NULL_VALUE, distinct from Java null) is what
        // actually triggers null-append semantics; append() only special-cases true Java null (arg1 ==
        // null) as "undefined", so a bound NULL_VALUE falls through to the array-wrapping branch and is
        // prepended, then converted back to Java null in the final output (outputConvertNulls, default
        // true).
        Jsonata expr = jsonata("$append($history, [1])");
        Frame frame = expr.createFrame();
        frame.bind("history", Jsonata.NULL_VALUE);

        Object result = expr.evaluate(new LinkedHashMap<>(), frame);

        List<Object> expected = new ArrayList<>();
        expected.add(null);
        expected.add(1);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("createFrame + bind reaches Map, List, String and Number values as $name, structurally intact")
    void bind_heterogeneousTypes_reachableAsFrameVariables() throws Exception {
        Jsonata expr = jsonata("{\"map\": $mapVar, \"list\": $listVar, \"str\": $strVar, \"num\": $numVar}");
        Frame frame = expr.createFrame();
        Map<String, Object> boundMap = Map.of("key", "value");
        List<Object> boundList = List.of(1, 2, 3);
        frame.bind("mapVar", boundMap);
        frame.bind("listVar", boundList);
        frame.bind("strVar", "hello");
        frame.bind("numVar", 42);

        Object result = expr.evaluate(new LinkedHashMap<>(), frame);

        assertThat(result).isEqualTo(Map.of("map", boundMap, "list", boundList, "str", "hello", "num", 42));
    }

    @Test
    @DisplayName("setRuntimeBounds aborts a runaway recursive expression instead of hanging")
    void setRuntimeBounds_runawayRecursion_isAborted() {
        Jsonata expr = jsonata("($f := function(){$f()}; $f())");
        Frame frame = expr.createFrame();
        frame.setRuntimeBounds(2000L, 50);

        assertThatThrownBy(() -> expr.evaluate(new LinkedHashMap<>(), frame)).isInstanceOf(JException.class);
    }

    @Test
    @DisplayName("setRuntimeBounds under generous bounds still lets a trivial expression succeed")
    void setRuntimeBounds_trivialExpression_succeedsUnderGenerousBounds() throws Exception {
        Jsonata expr = jsonata("1 + 1");
        Frame frame = expr.createFrame();
        frame.setRuntimeBounds(5000L, 500);

        Object result = expr.evaluate(new LinkedHashMap<>(), frame);

        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "F1: JSON echo of a legacy chat-completions request body round-trips structurally through JSONata evaluation")
    void evaluate_jsonEchoOfChatCompletionsBody_roundTripsWithDocumentedF1Caveat() throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", "You are helpful."),
                        Map.of("role", "user", "content", "Hi")));
        requestBody.put("temperature", 0.7);
        requestBody.put("zero_int", 0);
        requestBody.put("explicit_double_one", 1.0);
        requestBody.put("max_tokens", 1024);
        // 2^53 + 1 = 9007199254740993: smallest long not exactly representable as a double.
        requestBody.put("above_2_53", 9007199254740993L);
        requestBody.put("stream", false);
        requestBody.put("logprobs", true);
        requestBody.put("user", null);

        String json = objectMapper.writeValueAsString(requestBody);
        Jsonata expr = jsonata(json);

        Object result = expr.evaluate(new LinkedHashMap<>());

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> echoed = (Map<String, Object>) result;
        assertThat(echoed.get("messages"))
                .isEqualTo(List.of(
                        Map.of("role", "system", "content", "You are helpful."),
                        Map.of("role", "user", "content", "Hi")));
        assertThat(echoed.get("temperature")).isEqualTo(0.7);
        assertThat(echoed.get("zero_int")).isEqualTo(0);
        assertThat(echoed.get("max_tokens")).isEqualTo(1024);
        assertThat(echoed.get("stream")).isEqualTo(false);
        assertThat(echoed.get("logprobs")).isEqualTo(true);
        assertThat(echoed).containsKey("user");
        assertThat(echoed.get("user")).isNull();
        // F1 caveat: an explicit double literal with no fractional part (1.0) loses its
        // "double-ness" through JSONata evaluation and is echoed back as an integral value.
        assertThat(echoed.get("explicit_double_one")).isEqualTo(1);
        // F1 caveat: dashjoin JSONata represents numbers as Java double internally, so a long
        // above 2^53 loses precision on round-trip; the actual observed rounding is pinned here
        // rather than the mathematically exact input value.
        assertThat(((Number) echoed.get("above_2_53")).doubleValue()).isEqualTo(9.007199254740992E15);
    }

    @Test
    @DisplayName(
            "placeholder-injection precursor: a spliced JSON array literal evaluates correctly with $history bound and unbound")
    void evaluate_splicedArrayLiteralInObjectConstructor_evaluatesWithAndWithoutHistory() throws Exception {
        String splicedTurn = "[{\"role\":\"user\",\"content\":\"hi\"}]";
        String source = "{\"messages\": $append($history, " + splicedTurn + "), \"temperature\": 0.7}";
        Jsonata expr = jsonata(source);

        Frame unboundFrame = expr.createFrame();
        Object unboundResult = expr.evaluate(new LinkedHashMap<>(), unboundFrame);
        assertThat(unboundResult)
                .isEqualTo(Map.of("messages", List.of(Map.of("role", "user", "content", "hi")), "temperature", 0.7));

        Frame boundFrame = expr.createFrame();
        boundFrame.bind("history", List.of(Map.of("role", "assistant", "content", "hello")));
        Object boundResult = expr.evaluate(new LinkedHashMap<>(), boundFrame);
        assertThat(boundResult)
                .isEqualTo(Map.of(
                        "messages",
                        List.of(
                                Map.of("role", "assistant", "content", "hello"),
                                Map.of("role", "user", "content", "hi")),
                        "temperature",
                        0.7));
    }

    @Test
    @DisplayName("a JSON-string-escaped value spliced inside a string literal stays a plain string, not re-parsed")
    void evaluate_jsonStringEscapedValueInsideStringLiteral_staysPlainString() throws Exception {
        // Simulates embedded-in-literal placeholder substitution: a value containing quotes and
        // a backslash is JSON-string-escaped before being spliced into a larger string literal.
        String rawValue = "he said \"hi\" \\ bye";
        String escapedForSplice = objectMapper.writeValueAsString(rawValue);
        // Strip the surrounding quotes added by writeValueAsString since we splice inside our own
        // literal delimiters below.
        String escapedInner = escapedForSplice.substring(1, escapedForSplice.length() - 1);
        String source = "{\"content\": \"prefix: " + escapedInner + "\"}";
        Jsonata expr = jsonata(source);

        Object result = expr.evaluate(new LinkedHashMap<>());

        assertThat(result).isEqualTo(Map.of("content", "prefix: " + rawValue));
    }
}
