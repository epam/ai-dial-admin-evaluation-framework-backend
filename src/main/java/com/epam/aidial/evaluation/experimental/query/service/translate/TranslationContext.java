package com.epam.aidial.evaluation.experimental.query.service.translate;

import org.jooq.DSLContext;
import org.jooq.Table;

/**
 * Ambient context for a single filter translation, needed only to compile a {@code subquery}-valued
 * {@code in} operand into a nested {@code SELECT}:
 *
 * <ul>
 *   <li>{@code dsl} — the {@link DSLContext} the enclosing query runs on (the nested select shares it);
 *   <li>{@code table} — the enclosing entity's table (a same-entity subquery reuses it and its bindings);
 *   <li>{@code entity} — the enclosing entity's wire name, used to enforce the same-entity subquery rule.
 * </ul>
 *
 * <p>Callers that never contain a subquery (or must reject one) pass {@code null}.
 */
public record TranslationContext(DSLContext dsl, Table<?> table, String entity) {}
