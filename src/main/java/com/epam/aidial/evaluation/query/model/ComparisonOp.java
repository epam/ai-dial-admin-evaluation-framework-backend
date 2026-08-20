package com.epam.aidial.evaluation.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Comparison operator codes (§3). Wire values are the lowercase codes ({@code eq}, {@code ne}, …). */
public enum ComparisonOp {
    EQ("eq", false),
    NE("ne", true),
    CO("co", false),
    NC("nc", true),
    LT("lt", false),
    GT("gt", false),
    LE("le", false),
    GE("ge", false),
    IN("in", false);

    private final String code;
    private final boolean negated;

    ComparisonOp(String code, boolean negated) {
        this.code = code;
        this.negated = negated;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /**
     * Whether this operator asserts the <i>absence</i> of a match. A negated operator is satisfied by a
     * null operand (nothing cannot contain or equal something), so the translator makes it total rather
     * than letting SQL three-valued logic turn it into UNKNOWN. Positive operators keep three-valued
     * semantics: a null operand cannot satisfy a positive assertion.
     */
    public boolean negated() {
        return negated;
    }
}
