package com.epam.aidial.evaluation.data.db.repository.sql;

/**
 * ClickHouse {@code JSONExtract}/{@code JSONExtractRaw} return-type literals shared by every seam
 * that renders ClickHouse SQL against a JSON-text-as-{@code String} column (the analytics vendor
 * stores JSONB payloads as plain strings — see {@code V1.1__Init.sql}). Centralized here per
 * AGENTS.md's "non-configurable constants defined once per bounded context" rule instead of
 * duplicating the literal strings at each call site.
 *
 * <p>Consumers: {@code DialectAwareJsonPathAccessor} (text/numeric JSONB path access shared by meta and
 * analytics), {@code ClickHouseEvalSummaryRepository} (metric accessor overrides), and {@code
 * FilterTranslator} (array-element containment on ClickHouse).
 */
public final class ClickHouseTypeNames {

    private ClickHouseTypeNames() {}

    /** {@code JSONExtract} return-type literal for text extraction. */
    public static final String NULLABLE_STRING = "Nullable(String)";

    /** {@code JSONExtract} return-type literal for numeric extraction. */
    public static final String NULLABLE_FLOAT64 = "Nullable(Float64)";

    /** {@code JSONExtract} return-type literal for boolean extraction. */
    public static final String NULLABLE_BOOL = "Nullable(Bool)";

    /** {@code JSONExtract} return-type literal for decoding a JSON array of strings. */
    public static final String ARRAY_NULLABLE_STRING = "Array(Nullable(String))";
}
