package com.epam.aidial.evaluation.query.model;

/**
 * A single output column in {@code select} (§5.1): an expression and an optional alias. {@code as}
 * is optional when {@code expr} is a plain {@link FieldExpr} (the field name is used as the output
 * key); required for computed and aggregate expressions.
 */
public record OutputColumn(Expr expr, String as) {}
