package com.epam.aidial.evaluation.service.domain;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Spike: validates com.dashjoin:jsonata:0.9.9 against real LLM response body expressions.
 * Results document the final library choice (see design.md, Decision 8).
 */
@DisplayName("JSONata library spike (dashjoin:jsonata:0.9.9)")
class JsonataSpikeTest {

    private static final String SAMPLE_RESPONSE = """
            {
              "choices": [
                {
                  "message": {
                    "content": "Paris is the capital of France.",
                    "role": "assistant"
                  },
                  "finish_reason": "stop",
                  "index": 0
                }
              ],
              "usage": {
                "total_tokens": 25,
                "prompt_tokens": 10,
                "completion_tokens": 15
              },
              "model": "gpt-4"
            }
            """;

    private Map<String, Object> data;

    @BeforeEach
    void setUp() throws Exception {
        data = new ObjectMapper().readValue(SAMPLE_RESPONSE, new TypeReference<>() {});
    }

    @Test
    @DisplayName("evaluates nested path: choices[0].message.content")
    void evaluate_nestedArrayPath_returnsContent() throws Exception {
        Object result = jsonata("choices[0].message.content").evaluate(data);

        assertThat(result).isEqualTo("Paris is the capital of France.");
    }

    @Test
    @DisplayName("evaluates numeric field: usage.total_tokens")
    void evaluate_numericField_returnsNumber() throws Exception {
        Object result = jsonata("usage.total_tokens").evaluate(data);

        assertThat(result).isEqualTo(25);
    }

    @Test
    @DisplayName("evaluates string field in array: choices[0].finish_reason")
    void evaluate_arrayIndexedString_returnsFinishReason() throws Exception {
        Object result = jsonata("choices[0].finish_reason").evaluate(data);

        assertThat(result).isEqualTo("stop");
    }

    @Test
    @DisplayName("evaluates array extraction: choices.message.content extracts all contents")
    void evaluate_arrayExtraction_returnsAllValues() throws Exception {
        Object result = jsonata("choices.message.content").evaluate(data);

        assertThat(result).isEqualTo("Paris is the capital of France.");
    }

    @Test
    @DisplayName("returns null for missing path (no exception)")
    void evaluate_missingPath_returnsNull() throws Exception {
        Object result = jsonata("usage.missing_field").evaluate(data);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("returns null for out-of-bounds array index (no exception)")
    void evaluate_outOfBoundsIndex_returnsNull() throws Exception {
        Object result = jsonata("choices[99].message.content").evaluate(data);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("throws JException for syntactically invalid expression")
    void parse_invalidSyntax_throwsJsonataException() {
        assertThatThrownBy(() -> jsonata("choices[0.message.content")).isInstanceOf(JException.class);
    }

    @Test
    @DisplayName("returns null when evaluating against null input")
    void evaluate_nullInput_returnsNull() throws Exception {
        Object result = jsonata("choices[0].message.content").evaluate(null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("thread-safe: concurrent evaluations on the same expression produce correct results")
    void evaluate_concurrentCalls_threadSafe() throws Exception {
        Jsonata expression = jsonata("usage.total_tokens");
        var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();

        var threads = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> new Thread(() -> {
                    try {
                        Object result = expression.evaluate(data);
                        assertThat(result).isEqualTo(25);
                    } catch (Exception e) {
                        errors.add(e);
                    }
                }))
                .toList();

        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }

        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("Jackson ObjectMapper-parsed data is compatible with JSONata evaluation")
    void evaluate_jacksonParsedData_compatible() throws Exception {
        // Validates the full pipeline: JSON string → ObjectMapper → JSONata evaluate
        String json = """
                {"score": 0.95, "labels": ["a", "b"], "nested": {"x": true}}
                """;
        Map<String, Object> parsed = new ObjectMapper().readValue(json, new TypeReference<>() {});

        assertThat(jsonata("score").evaluate(parsed)).isEqualTo(0.95);
        assertThat(jsonata("labels[0]").evaluate(parsed)).isEqualTo("a");
        assertThat(jsonata("nested.x").evaluate(parsed)).isEqualTo(true);
    }
}
