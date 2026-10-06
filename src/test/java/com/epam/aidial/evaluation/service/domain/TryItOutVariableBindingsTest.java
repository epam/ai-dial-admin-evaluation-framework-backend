package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TryItOutVariableBindingsTest {

    private final TryItOutVariableBindings bindings = new TryItOutVariableBindings();

    @Test
    @DisplayName("Every index 0..requestCount-1 is present, in order")
    void shouldContainAllIndicesInOrder() {
        final Map<Integer, List<InputBindingDto>> result = bindings.toBindingsByRequest(Map.of(1, Map.of("a", "x")), 3);

        assertThat(result.keySet()).containsExactly(0, 1, 2);
        assertThat(result.get(1)).hasSize(1);
        assertThat(result.get(1).get(0).getTemplateVariable()).isEqualTo("a");
        assertThat(result.get(1).get(0).getConstantValue()).isEqualTo("x");
    }

    @Test
    @DisplayName("Absent and null inner maps yield empty lists")
    void shouldYieldEmptyListForAbsentAndNullEntries() {
        final Map<Integer, Map<String, Object>> variables = new HashMap<>();
        variables.put(1, null);

        final Map<Integer, List<InputBindingDto>> result = bindings.toBindingsByRequest(variables, 3);

        assertThat(result.get(0)).isEmpty();
        assertThat(result.get(1)).isEmpty();
        assertThat(result.get(2)).isEmpty();
    }

    @Test
    @DisplayName("Blank names and null values are skipped")
    void shouldSkipBlankNamesAndNullValues() {
        final Map<String, Object> inner = new LinkedHashMap<>();
        inner.put(" ", "v");
        inner.put("", "v");
        inner.put("nullValue", null);
        inner.put("kept", 1);

        final Map<Integer, List<InputBindingDto>> result = bindings.toBindingsByRequest(Map.of(0, inner), 1);

        assertThat(result.get(0)).hasSize(1);
        assertThat(result.get(0).get(0).getTemplateVariable()).isEqualTo("kept");
    }

    @Test
    @DisplayName("Index equal to requestCount is rejected with the exact message")
    void shouldRejectIndexEqualToRequestCount() {
        assertThatThrownBy(() -> bindings.toBindingsByRequest(Map.of(2, Map.of("a", "x")), 2))
                .isInstanceOf(ValidationException.class)
                .hasMessage("variables: request index 2 is out of range (chain length 2)");
    }

    @Test
    @DisplayName("Negative index is rejected with the exact message")
    void shouldRejectNegativeIndex() {
        assertThatThrownBy(() -> bindings.toBindingsByRequest(Map.of(-1, Map.of("a", "x")), 2))
                .isInstanceOf(ValidationException.class)
                .hasMessage("variables: request index -1 is out of range (chain length 2)");
    }

    @Test
    @DisplayName("Null index is rejected")
    void shouldRejectNullIndex() {
        final Map<Integer, Map<String, Object>> variables = new HashMap<>();
        variables.put(null, Map.of("a", "x"));

        assertThatThrownBy(() -> bindings.toBindingsByRequest(variables, 2))
                .isInstanceOf(ValidationException.class)
                .hasMessage("variables: request index must not be null");
    }

    @Test
    @DisplayName("Key 1 is rejected when requestCount is 1")
    void shouldRejectIndexOneForSingleRequest() {
        assertThatThrownBy(() -> bindings.toBindingsByRequest(Map.of(1, Map.of("a", "x")), 1))
                .isInstanceOf(ValidationException.class)
                .hasMessage("variables: request index 1 is out of range (chain length 1)");
    }
}
