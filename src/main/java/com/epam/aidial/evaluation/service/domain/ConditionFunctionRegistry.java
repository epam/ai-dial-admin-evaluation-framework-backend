package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Discovers all {@link ConditionFunction} beans and resolves a bare {@code name()} condition to the one
 * matching its (exact-case) name. Mirrors the {@code QueryFunctionRegistry} SPI pattern: adding a
 * custom condition function is just adding a {@link ConditionFunction} bean — no edits here. Ships
 * empty (no built-in functions yet); duplicate names fail fast at startup.
 */
@Component
@LogExecution
public class ConditionFunctionRegistry {

    private final Map<String, ConditionFunction> functionsByName;

    public ConditionFunctionRegistry(List<ConditionFunction> functions) {
        final Map<String, ConditionFunction> byName = new HashMap<>();
        for (final ConditionFunction function : functions) {
            final ConditionFunction duplicate = byName.put(function.name(), function);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate condition function registered for name: " + function.name());
            }
        }
        this.functionsByName = byName;
    }

    public boolean contains(String name) {
        return functionsByName.containsKey(name);
    }

    public ConditionFunction get(String name) {
        return functionsByName.get(name);
    }
}
