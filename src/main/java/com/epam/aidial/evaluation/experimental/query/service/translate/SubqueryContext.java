package com.epam.aidial.evaluation.experimental.query.service.translate;

import org.jooq.DSLContext;

/**
 * Everything {@link FilterTranslator} needs to compile a {@code subquery}-valued {@code in}: the
 * enclosing entity (for the same-entity check) and a {@link QueryCompiler} to build the nested select.
 * Passed as an ordinary method parameter, threaded through the recursive translation calls — never
 * stored as a field, never a {@code ThreadLocal}.
 */
record SubqueryContext(DSLContext dsl, String entity, QueryCompiler compiler) {}
