package com.epam.aidial.evaluation.experimental.query.model;

import java.util.List;

/**
 * A function call (§4.4): arbitrary arity and nesting; {@code args} are themselves expressions.
 * Aggregates are just functions ({@code sum}, {@code count}, …); {@code count} with no args is
 * {@code COUNT(*)}. {@code distinct} applies argument-level deduplication (e.g. {@code
 * COUNT(DISTINCT x)}). The function set is an allowlist enforced by the validation layer (out of
 * scope) — an unregistered name is rejected before any SQL is built.
 */
public record FnExpr(String name, boolean distinct, List<Expr> args) implements Expr {}
