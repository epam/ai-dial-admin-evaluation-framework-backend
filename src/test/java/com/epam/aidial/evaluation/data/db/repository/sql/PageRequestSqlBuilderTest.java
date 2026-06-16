package com.epam.aidial.evaluation.data.db.repository.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class PageRequestSqlBuilderTest {

    private static final DSLContext DSL_CTX = DSL.using(SQLDialect.POSTGRES);

    @Test
    void shouldReturnCorrectLimitForPageRequest() {
        PageRequest pageRequest = PageRequest.of(0, 20);

        int limit = PageRequestSqlBuilder.limit(pageRequest);

        assertThat(limit).isEqualTo(20);
    }

    @Test
    void shouldReturnCorrectOffsetForPageZero() {
        PageRequest pageRequest = PageRequest.of(0, 20);

        long offset = PageRequestSqlBuilder.offset(pageRequest);

        assertThat(offset).isEqualTo(0L);
    }

    @Test
    void shouldReturnCorrectOffsetForPageTwo() {
        PageRequest pageRequest = PageRequest.of(2, 10);

        long offset = PageRequestSqlBuilder.offset(pageRequest);

        assertThat(offset).isEqualTo(20L);
    }

    @Test
    void shouldReturnNegativeOffsetWhenPageIsNegative() {
        // PageRequestSqlBuilder.offset() does not validate the page number;
        // validation is done by PageRequest.getOffset() or at the controller layer.
        // We verify the raw arithmetic here.
        PageRequest pageRequest = PageRequest.of(-1, 10);

        long offset = PageRequestSqlBuilder.offset(pageRequest);

        assertThat(offset).isEqualTo(-10L);
    }

    @Test
    void shouldThrowWhenSizeIsZeroForLimit() {
        PageRequest pageRequest = PageRequest.of(0, 0);

        assertThatThrownBy(() -> PageRequestSqlBuilder.limit(pageRequest)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnTrueConditionWhenCursorIsNull() {
        Field<Long> createdAt = DSL.field("created_at_ms", Long.class);
        Field<String> id = DSL.field("id", String.class);

        Condition condition = PageRequestSqlBuilder.cursorPredicate(null, createdAt, id);

        String rendered = DSL_CTX.renderInlined(condition);
        assertThat(rendered)
                .satisfiesAnyOf(
                        r -> assertThat(r).isEqualTo("1 = 1"),
                        r -> assertThat(r).isEqualTo("true"));
    }

    @Test
    void shouldReturnCompositePredicateWhenCursorIsPresent() {
        UUID cursorId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Cursor cursor = new Cursor(1700000000000L, cursorId);
        Field<Long> createdAt = DSL.field("created_at_ms", Long.class);
        Field<String> id = DSL.field("id", String.class);

        Condition condition = PageRequestSqlBuilder.cursorPredicate(cursor, createdAt, id);

        String sql = DSL_CTX.renderInlined(condition);
        assertThat(sql).contains("created_at_ms");
        assertThat(sql).contains("1700000000000");
        assertThat(sql).contains("550e8400-e29b-41d4-a716-446655440000");
        assertThat(sql).containsIgnoringCase("or");
    }
}
