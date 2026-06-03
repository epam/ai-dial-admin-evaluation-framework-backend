package com.epam.aidial.evaluation.data.db.repository.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.model.pagination.SortKey;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class OrderByBuilderTest {

    private static final DSLContext DSL_CTX = DSL.using(SQLDialect.POSTGRES);

    private static final SortSpec SPEC = SortSpec.of(
            Map.of(
                    "id", DSL.field("id"),
                    "name", DSL.field("name"),
                    "createdAt", DSL.field("created_at_ms")),
            List.of(SortKey.builder()
                    .field("createdAt")
                    .direction(PageRequest.SortDirection.DESC)
                    .build()));

    private final OrderByBuilder builder = new OrderByBuilder();

    @Test
    void shouldUseDefaultSortWhenNoKeysProvided() {
        List<SortField<?>> orderBy = builder.build(List.of(), SPEC);

        assertThat(orderBy).hasSize(2);
        String rendered = orderBy.stream()
                .map(DSL_CTX::renderInlined)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        assertThat(rendered).containsIgnoringCase("created_at_ms desc");
        assertThat(rendered).containsIgnoringCase("id asc");
    }

    @Test
    void shouldAddTieBreakerWhenIdNotProvided() {
        List<SortField<?>> orderBy = builder.build(
                List.of(SortKey.builder()
                        .field("name")
                        .direction(PageRequest.SortDirection.ASC)
                        .build()),
                SPEC);

        assertThat(orderBy).hasSize(2);
        String rendered = orderBy.stream()
                .map(DSL_CTX::renderInlined)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        assertThat(rendered).containsIgnoringCase("name asc");
        assertThat(rendered).containsIgnoringCase("id asc");
    }

    @Test
    void shouldNotDuplicateIdWhenAlreadyIncluded() {
        List<SortField<?>> orderBy = builder.build(
                List.of(SortKey.builder()
                        .field("id")
                        .direction(PageRequest.SortDirection.DESC)
                        .build()),
                SPEC);

        assertThat(orderBy).hasSize(1);
        String rendered = DSL_CTX.renderInlined(orderBy.get(0));
        assertThat(rendered).containsIgnoringCase("id desc");
    }

    @Test
    void shouldRejectUnknownField() {
        assertThatThrownBy(() -> builder.build(
                        List.of(SortKey.builder()
                                .field("unknown")
                                .direction(PageRequest.SortDirection.ASC)
                                .build()),
                        SPEC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown sort field");
    }
}
