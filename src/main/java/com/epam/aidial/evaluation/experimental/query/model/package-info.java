/**
 * Record-based object model for the experimental structured query DSL (request side).
 *
 * <p>Mirrors the §1–§7 wire grammar of {@code docs/experimental/structured-query-model-v8.html}: a
 * single structured JSON query (filter / sort / pagination / projection / aggregation) that
 * clients POST and the server validates against a per-entity allowlist and translates to
 * parameterized SQL. This package contains <strong>only</strong> the immutable request shape;
 * validation (§9), SQL translation, and the response envelopes (§7.4/§8.2) live in the sibling
 * {@code service}, {@code service.translate}, {@code service.repository}, and {@code web} packages.
 *
 * <p>{@link com.epam.aidial.evaluation.experimental.query.model.Expr} (by {@code type}) and {@link
 * com.epam.aidial.evaluation.experimental.query.model.PageSpec} (by {@code type}) use declarative
 * Jackson NAME discriminators. {@link
 * com.epam.aidial.evaluation.experimental.query.model.FilterNode} is routed by {@link
 * com.epam.aidial.evaluation.experimental.query.model.FilterNodeDeserializer} because its {@code op}
 * key is both discriminator and operator data. A comparison's operands are general {@code Expr}s on
 * both sides (a column is a {@code FieldExpr}); there is no dedicated property-reference type.
 */
package com.epam.aidial.evaluation.experimental.query.model;
