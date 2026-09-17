package com.epam.aidial.evaluation.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A SQL-style {@code CASE WHEN ... THEN ... ELSE ... END} expression: an ordered list of {@link
 * WhenClause} clauses evaluated in order, falling back to {@code else} when none match. Supported
 * only for {@code dial_usage_log} queries built directly by {@code AdasCostQueryBuilder} — {@link
 * com.epam.aidial.evaluation.query.service.translate.ExprTranslator} rejects it for every internal
 * entity query.
 */
public record CaseExpr(
        List<WhenClause> when, @JsonProperty("else") Expr elseExpr) implements Expr {}
