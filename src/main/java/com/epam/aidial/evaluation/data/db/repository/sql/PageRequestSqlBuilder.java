package com.epam.aidial.evaluation.data.db.repository.sql;

import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

public final class PageRequestSqlBuilder {

    private PageRequestSqlBuilder() {}

    public static int limit(PageRequest pageRequest) {
        return pageRequest.getValidatedSize();
    }

    public static long offset(PageRequest pageRequest) {
        return (long) pageRequest.getPage() * pageRequest.getValidatedSize();
    }

    public static Condition cursorPredicate(Cursor cursor, Field<Long> createdAtField, Field<String> idField) {
        if (cursor == null) {
            return DSL.trueCondition();
        }
        return createdAtField
                .lt(cursor.createdAt())
                .or(createdAtField
                        .eq(cursor.createdAt())
                        .and(idField.lt(cursor.id().toString())));
    }
}
