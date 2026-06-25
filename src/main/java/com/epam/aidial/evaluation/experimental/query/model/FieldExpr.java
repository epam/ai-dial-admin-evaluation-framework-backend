package com.epam.aidial.evaluation.experimental.query.model;

/** A column reference (§4.1): {@code { "type": "field", "name": "amount" }}. */
public record FieldExpr(String name) implements Expr {}
