package com.epam.aidial.evaluation.query.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A nested {@link StructuredQuery}, usable anywhere any other expression is valid: as the right
 * operand of {@code in} (set membership), as a scalar comparison operand, as a {@code select}
 * projection, or as a function argument. Compiled to a nested {@code SELECT} whose <b>first</b>
 * select column is the resulting value — the membership key when used with {@code in}, the scalar
 * result everywhere else. It may select additional columns purely to drive its own
 * {@code ORDER BY}/{@code LIMIT} (e.g. {@code max(computed_at_ms)} to take the latest N groups). The
 * subquery may target any registered entity, including one on a different datasource than the
 * enclosing query — a cross-datasource subquery simply fails at the database with a normal SQL
 * error, mapped to HTTP 400 like any other grammar/type mismatch.
 */
public record SubqueryExpr(
        @Schema(
                description = "Nested structured query compiled to a nested SELECT; its first select column is"
                        + " the resulting value (the 'in' membership key when used with 'in', the scalar result"
                        + " otherwise).")
        StructuredQuery query)
        implements Expr {}
