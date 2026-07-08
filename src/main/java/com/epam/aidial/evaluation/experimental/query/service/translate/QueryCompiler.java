package com.epam.aidial.evaluation.experimental.query.service.translate;

import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import org.jooq.Record;
import org.jooq.SelectQuery;

/**
 * Compiles a {@link StructuredQuery} into an executable select, closing over whatever {@code dsl}/
 * {@code table}/{@code bindings} the caller already has in scope. Never a Spring bean — callers needing
 * this capability pass it as a plain lambda/method-reference value, so no bean dependency is incurred.
 */
@FunctionalInterface
public interface QueryCompiler {

    SelectQuery<Record> compile(StructuredQuery query);
}
