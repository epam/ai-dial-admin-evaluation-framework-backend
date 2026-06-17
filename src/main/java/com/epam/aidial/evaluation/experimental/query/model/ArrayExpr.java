package com.epam.aidial.evaluation.experimental.query.model;

import java.util.List;

/**
 * An ordered collection expression (§4.6): {@code { "type": "array", "items": [ <expr>, … ] }}. The
 * wire key is deliberately {@code items} (a collection), distinct from a function's {@code args}
 * (ordered positional arguments). Items are themselves {@link Expr expressions}; the most common use
 * is the right operand of {@code in} (§8.5), but an array may appear wherever an expression may.
 *
 * <p>Item-kind admissibility, homogeneity, and empty-array handling are type/semantic constraints
 * resolved by the (out-of-scope) validation layer — the grammar only fixes the structure.
 */
public record ArrayExpr(List<Expr> items) implements Expr {}
