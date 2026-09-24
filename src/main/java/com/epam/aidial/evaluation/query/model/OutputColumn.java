package com.epam.aidial.evaluation.query.model;

/**
 * A single output column in {@code select} (§5.1): an expression and an optional alias. {@code as}
 * is optional when {@code expr} is a plain {@link FieldExpr} (the field name is used as the output
 * key); required for computed and aggregate expressions.
 */
public record OutputColumn(Expr expr, String as) {

    /**
     * The key this column carries in a {@code row}-mode result row: {@code as} when present, else the
     * field name of a plain {@link FieldExpr}, else {@code null} (an unaliased computed expression).
     */
    public String outputKey() {
        if (as != null) {
            return as;
        }
        return expr instanceof FieldExpr(String fieldName) ? fieldName : null;
    }
}
