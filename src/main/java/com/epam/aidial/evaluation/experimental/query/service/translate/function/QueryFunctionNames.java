package com.epam.aidial.evaluation.experimental.query.service.translate.function;

/**
 * Names of query-DSL functions that carry meaning <em>outside</em> their own registration — i.e. that a
 * translator has to recognise structurally rather than just look up. Only the case-normalizing pair
 * qualifies today: {@code FilterTranslator} reads {@code lower}/{@code upper} over an {@code ARRAY}-typed
 * field as a case-insensitivity hint for {@code co}/{@code nc} instead of translating the call. Keeping the
 * two spellings here — rather than repeating the literals in both the registration and the routing check —
 * is what makes those two sites impossible to drift apart.
 *
 * <p>Every other function name lives only in its {@link QueryFunction} registration in
 * {@link BuiltInQueryFunctions}, which is its single definition.
 *
 * <p>Wire names are matched case-insensitively, exactly as {@link QueryFunctionRegistry} resolves them,
 * so a client sending {@code LOWER} routes the same way as one sending {@code lower}.
 */
public final class QueryFunctionNames {

    public static final String LOWER = "lower";
    public static final String UPPER = "upper";

    private QueryFunctionNames() {}

    /** True when {@code name} is a case-normalizing function name, ignoring case (null-safe). */
    public static boolean isCaseNormalizing(String name) {
        return LOWER.equalsIgnoreCase(name) || UPPER.equalsIgnoreCase(name);
    }
}
