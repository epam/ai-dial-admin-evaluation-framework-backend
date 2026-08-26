package com.epam.aidial.evaluation.query.model;

/**
 * A runtime parameter (§4.3): {@code { "type": "param", "name": "min_threshold" }}, resolved at
 * execution from a trusted server-side context and emitted as a bind parameter.
 */
public record ParamExpr(String name) implements Expr {}
