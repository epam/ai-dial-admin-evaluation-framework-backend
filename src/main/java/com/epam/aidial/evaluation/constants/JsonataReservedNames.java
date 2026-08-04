package com.epam.aidial.evaluation.constants;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Names reserved for the JSONata evaluation frame used by request-template and response-column
 * evaluation.
 *
 * <p>Response columns become named frame variables (e.g. a column named {@code history} is bound
 * as {@code $history}) that a downstream turn's JSONata expression can reference. A response
 * column name that shadows a JSONata built-in function name would silently change the meaning of
 * any expression calling that function (the frame binding wins over the built-in), and a column
 * named {@code request}/{@code response} would collide with the framework-bound {@code $request}/
 * {@code $response} frame variables (see the {@code response-columns} spec). Both are rejected at
 * suite create/update time (HTTP 400) rather than allowed to silently shadow.
 */
public final class JsonataReservedNames {

    private JsonataReservedNames() {}

    /** JSONata 1.8 built-in function names (without the leading {@code $}). */
    public static final Set<String> BUILT_IN_FUNCTION_NAMES = Collections.unmodifiableSet(new HashSet<>(Set.of(
            "string",
            "length",
            "substring",
            "substringBefore",
            "substringAfter",
            "uppercase",
            "lowercase",
            "trim",
            "pad",
            "contains",
            "split",
            "join",
            "match",
            "replace",
            "eval",
            "base64encode",
            "base64decode",
            "encodeUrlComponent",
            "encodeUrl",
            "decodeUrlComponent",
            "decodeUrl",
            "number",
            "abs",
            "floor",
            "ceil",
            "round",
            "power",
            "sqrt",
            "random",
            "formatNumber",
            "formatBase",
            "formatInteger",
            "parseInteger",
            "sum",
            "max",
            "min",
            "average",
            "boolean",
            "not",
            "exists",
            "count",
            "append",
            "sort",
            "reverse",
            "shuffle",
            "distinct",
            "zip",
            "keys",
            "lookup",
            "spread",
            "merge",
            "sift",
            "each",
            "error",
            "assert",
            "type",
            "map",
            "filter",
            "single",
            "reduce",
            "sigma",
            "now",
            "millis",
            "fromMillis",
            "toMillis")));

    /**
     * Frame variable names reserved for the framework-bound request/response correlation frame
     * (see {@code response-columns} spec, "Request/response frame for response column
     * extraction").
     */
    public static final Set<String> FRAME_RESERVED_NAMES = Collections.unmodifiableSet(Set.of("request", "response"));

    /**
     * Combined set of names a response column's {@code name} must not collide with: JSONata
     * built-in function names plus the reserved frame variable names.
     */
    public static final Set<String> RESERVED_COLUMN_NAMES = Collections.unmodifiableSet(union());

    private static Set<String> union() {
        Set<String> combined = new HashSet<>(BUILT_IN_FUNCTION_NAMES);
        combined.addAll(FRAME_RESERVED_NAMES);
        return combined;
    }
}
