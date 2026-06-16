package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DashjoinJsonataEvaluationService - Array Format")
class DashjoinJsonataArrayTest {

    private DashjoinJsonataEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new DashjoinJsonataEvaluationService(new ObjectMapper());
    }

    @Test
    @DisplayName("evaluates first element of top-level array via $[0].text")
    void evaluate_topLevelArray_firstElement() {
        String json = """
                [{"text":"hello"},{"text":"world"}]
                """;

        Object result = service.evaluate("$[0].text", json);

        assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("evaluates last element of top-level array via $[-1].text")
    void evaluate_topLevelArray_lastElement() {
        String json = """
                [{"text":"hello"},{"text":"world"}]
                """;

        Object result = service.evaluate("$[-1].text", json);

        assertThat(result).isEqualTo("world");
    }

    @Test
    @DisplayName("evaluates all elements of top-level array via $.text")
    void evaluate_topLevelArray_allElements() {
        String json = """
                [{"text":"hello"},{"text":"world"}]
                """;

        Object result = service.evaluate("$.text", json);

        assertThat(result).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> resultList = (List<String>) result;
        assertThat(resultList).containsExactly("hello", "world");
    }

    @Test
    @DisplayName("evaluates non-array JSON object (regression check)")
    void evaluate_topLevelObject_stillWorks() {
        String json = """
                {"text":"hello","count":42}
                """;

        Object textResult = service.evaluate("text", json);
        Object countResult = service.evaluate("count", json);

        assertThat(textResult).isEqualTo("hello");
        assertThat(countResult).isEqualTo(42);
    }
}
