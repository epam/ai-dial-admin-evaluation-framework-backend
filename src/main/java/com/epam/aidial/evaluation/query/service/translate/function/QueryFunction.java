package com.epam.aidial.evaluation.query.service.translate.function;

import com.epam.aidial.evaluation.query.model.FnExpr;
import java.util.function.BiFunction;
import org.jooq.Field;

/**
 * A query-DSL function: translates an {@link FnExpr} (its args resolved through the supplied
 * {@link FunctionContext}) into a jOOQ {@link Field}. Functions are pluggable — register a new one
 * as a Spring bean and {@link QueryFunctionRegistry} picks it up automatically; the translator never
 * hardcodes the function set.
 */
public interface QueryFunction {

    /** The lowercase wire name this function is invoked by (e.g. {@code avg}, {@code mean}). */
    String name();

    /** Translates {@code fn} into a jOOQ field, using {@code ctx} to resolve/translate its arguments. */
    Field<?> translate(FnExpr fn, FunctionContext ctx);

    /** Convenience factory for declaring a function inline (used for the built-in catalog beans). */
    static QueryFunction of(String name, BiFunction<FnExpr, FunctionContext, Field<?>> impl) {
        return new QueryFunction() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Field<?> translate(FnExpr fn, FunctionContext ctx) {
                return impl.apply(fn, ctx);
            }
        };
    }
}
