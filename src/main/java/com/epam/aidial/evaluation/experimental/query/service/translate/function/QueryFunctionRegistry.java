package com.epam.aidial.evaluation.experimental.query.service.translate.function;

import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jooq.Field;
import org.springframework.stereotype.Component;

/**
 * Discovers all {@link QueryFunction} beans and dispatches an {@link FnExpr} to the one matching its
 * (lowercased) name. Replaces the former hardcoded {@code switch} in {@code ExprTranslator}: adding a
 * function is just adding a {@link QueryFunction} bean — no edits here.
 */
@Component
@LogExecution
public class QueryFunctionRegistry {

    private final Map<String, QueryFunction> functionsByName;

    public QueryFunctionRegistry(List<QueryFunction> functions) {
        final Map<String, QueryFunction> byName = new TreeMap<>();
        for (final QueryFunction function : functions) {
            final String name = function.name().toLowerCase(Locale.ROOT);
            final QueryFunction duplicate = byName.put(name, function);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate query function registered for name: " + name);
            }
        }
        this.functionsByName = byName;
    }

    public Field<?> translate(FnExpr fn, FunctionContext ctx) {
        final String name = fn.name() == null ? "" : fn.name().toLowerCase(Locale.ROOT);
        final QueryFunction function = functionsByName.get(name);
        if (function == null) {
            throw new ValidationException("unsupported function '" + fn.name() + "' in query expression");
        }
        return function.translate(fn, ctx);
    }
}
