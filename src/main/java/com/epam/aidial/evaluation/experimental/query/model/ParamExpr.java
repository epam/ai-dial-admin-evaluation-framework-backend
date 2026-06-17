package com.epam.aidial.evaluation.experimental.query.model;

/**
 * A runtime parameter (§4.3): {@code { "type": "param", "name": "min_threshold" }}, resolved at
 * execution from a trusted server-side context and emitted as a bind parameter.
 */
// TODO(D10): the param source/registry and its trust boundary are undefined; resolution is a
// translation-stage concern, out of scope for this model.
public record ParamExpr(String name) implements Expr {}
