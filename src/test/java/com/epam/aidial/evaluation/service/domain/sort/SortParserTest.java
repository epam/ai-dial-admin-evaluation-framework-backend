package com.epam.aidial.evaluation.service.domain.sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.model.pagination.SortKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class SortParserTest {

    private final SortParser parser = new SortParser();

    @Test
    void shouldReturnEmptyListWhenNull() {
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenEmpty() {
        assertThat(parser.parse(List.of())).isEmpty();
    }

    @Test
    void shouldParseSingleKeyWithDefaultDirection() {
        List<SortKey> sort = parser.parse(List.of("name"));

        assertThat(sort)
                .containsExactly(SortKey.builder()
                        .field("name")
                        .direction(PageRequest.SortDirection.ASC)
                        .build());
    }

    @Test
    void shouldParseSingleKeyWithExplicitDirection() {
        List<SortKey> sort = parser.parse(List.of("name,desc"));

        assertThat(sort)
                .containsExactly(SortKey.builder()
                        .field("name")
                        .direction(PageRequest.SortDirection.DESC)
                        .build());
    }

    @Test
    void shouldPreserveMultiKeyPrecedenceOrder() {
        List<SortKey> sort = parser.parse(List.of("status,asc", "name,desc"));

        assertThat(sort)
                .containsExactly(
                        SortKey.builder()
                                .field("status")
                                .direction(PageRequest.SortDirection.ASC)
                                .build(),
                        SortKey.builder()
                                .field("name")
                                .direction(PageRequest.SortDirection.DESC)
                                .build());
    }

    @Test
    void shouldRecombineSpringTokenizedShape() {
        // Simulate Spring MVC binding where "sort=status,asc&sort=name,desc"
        // may arrive as ["status","asc","name","desc"].
        List<SortKey> sort = parser.parse(List.of("status", "asc", "name", "desc"));

        assertThat(sort)
                .containsExactly(
                        SortKey.builder()
                                .field("status")
                                .direction(PageRequest.SortDirection.ASC)
                                .build(),
                        SortKey.builder()
                                .field("name")
                                .direction(PageRequest.SortDirection.DESC)
                                .build());
    }

    @Test
    void shouldRejectBlankValue() {
        assertThatThrownBy(() -> parser.parse(List.of("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void shouldRejectInvalidDirection() {
        assertThatThrownBy(() -> parser.parse(List.of("name,up")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort direction");
    }

    @Test
    void shouldRejectTooManyParts() {
        assertThatThrownBy(() -> parser.parse(List.of("a,b,c")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort parameter");
    }
}
