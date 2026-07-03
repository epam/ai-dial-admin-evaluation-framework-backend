package com.epam.aidial.evaluation.experimental.query.model;

import java.util.List;

/**
 * A predicate node (§3): {@code { "op": "<code>", "args": [ <expr>, <expr>, … ] }}.
 *
 * <p>{@code args} is a faithful, positional list of {@link Expr expressions}. The model makes no
 * assumption about what occupies each position — both sides of a comparison are general expressions
 * (a column is just a {@link FieldExpr}, e.g. {@code length(test_suite_id) = 3} is
 * {@code fn(field) eq value}). {@code in} is an ordinary binary predicate whose right operand is
 * typically an {@link ArrayExpr} (§8.5). Each operator's expected arity and argument shapes are a
 * validation concern, enforced by the (out-of-scope) validation layer rather than structurally here.
 */
public record ComparisonNode(ComparisonOp op, List<Expr> args) implements FilterNode {}
