package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConditionFunctionRegistry")
class ConditionFunctionRegistryTest {

    private static ConditionFunction fn(String name) {
        return new ConditionFunction() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean evaluate(ConditionContext context) {
                return true;
            }
        };
    }

    @Test
    @DisplayName("Empty registry resolves nothing")
    void emptyRegistry() {
        ConditionFunctionRegistry registry = new ConditionFunctionRegistry(List.of());

        assertThat(registry.contains("isLastTurn")).isFalse();
        assertThat(registry.get("isLastTurn")).isNull();
    }

    @Test
    @DisplayName("Registered function is resolvable by exact name")
    void registeredFunction() {
        ConditionFunction fn = fn("isLastTurn");
        ConditionFunctionRegistry registry = new ConditionFunctionRegistry(List.of(fn));

        assertThat(registry.contains("isLastTurn")).isTrue();
        assertThat(registry.get("isLastTurn")).isSameAs(fn);
    }

    @Test
    @DisplayName("Duplicate function names fail fast at construction")
    void duplicateNamesRejected() {
        assertThatThrownBy(() -> new ConditionFunctionRegistry(List.of(fn("dup"), fn("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }
}
