package com.epam.aidial.evaluation.experimental.query.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A nested {@link StructuredQuery} usable only as the right operand of an {@code in} predicate (§4):
 * {@code <field> in (<subquery>)}. Compiled to a nested {@code SELECT}: the subquery must target the
 * same entity as the enclosing query (reusing its table + bindings), and its <b>first</b> select
 * column is the membership key projected into the {@code IN}. It may select additional columns purely
 * to drive its own {@code ORDER BY}/{@code LIMIT} (e.g. {@code max(computed_at_ms)} to take the latest
 * N groups). Anywhere other than an {@code in} right operand it is rejected.
 */
public record SubqueryExpr(
        @Schema(
                description = "Nested structured query (same entity) compiled to a nested SELECT; its first"
                        + " select column is the 'in' membership set.")
        StructuredQuery query)
        implements Expr {}
