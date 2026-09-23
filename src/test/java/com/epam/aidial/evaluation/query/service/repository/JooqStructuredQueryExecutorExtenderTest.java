package com.epam.aidial.evaluation.query.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JooqStructuredQueryExecutorExtenderTest {

    private static final StructuredQuery QUERY =
            new StructuredQuery("test_suite_runs", null, QueryMode.ROW, false, List.of(), null, null, null, null);

    private final JooqStructuredQueryExecutor executor = mock(JooqStructuredQueryExecutor.class);

    @Test
    @DisplayName("with no extenders, the page is returned exactly as the executor produced it")
    void noExtendersReturnsPageAsIs() {
        final QueryResultPage page = page();
        when(executor.execute(QUERY)).thenReturn(page);
        final JooqStructuredQueryExecutorExtender coordinator =
                new JooqStructuredQueryExecutorExtender(executor, List.of());

        final QueryResultPage result = coordinator.execute(QUERY);

        assertThat(result).isSameAs(page);
    }

    @Test
    @DisplayName("one extender adds keys to every row without disturbing the rest of the page")
    void oneExtenderAddsKeys() {
        final QueryResultPage page = page();
        when(executor.execute(QUERY)).thenReturn(page);
        final JooqStructuredQueryExecutorExtender coordinator =
                new JooqStructuredQueryExecutorExtender(executor, List.of(addsKey()));

        final QueryResultPage result = coordinator.execute(QUERY);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)).containsEntry("id", "row-1").containsEntry("derived", "value");
        assertThat(result.totalCount()).isEqualTo(page.totalCount());
    }

    @Test
    @DisplayName("a throwing extender yields the unextended page")
    void throwingExtenderYieldsUnextendedPage() {
        final QueryResultPage page = page();
        when(executor.execute(QUERY)).thenReturn(page);
        final JooqStructuredQueryExecutorExtender coordinator =
                new JooqStructuredQueryExecutorExtender(executor, List.of(throwing()));

        final QueryResultPage result = coordinator.execute(QUERY);

        assertThat(result).isSameAs(page);
    }

    @Test
    @DisplayName("a throwing extender does not suppress a later extender")
    void throwingExtenderDoesNotSuppressLaterExtender() {
        final QueryResultPage page = page();
        when(executor.execute(QUERY)).thenReturn(page);
        final JooqStructuredQueryExecutorExtender coordinator =
                new JooqStructuredQueryExecutorExtender(executor, List.of(throwing(), addsKey()));

        final QueryResultPage result = coordinator.execute(QUERY);

        assertThat(result.rows().get(0)).containsEntry("derived", "value");
    }

    @Test
    @DisplayName("an extender returning null yields the unextended page (treated the same as a thrown exception)")
    void nullReturningExtenderYieldsUnextendedPage() {
        final QueryResultPage page = page();
        when(executor.execute(QUERY)).thenReturn(page);
        final QueryResultPageExtender returnsNull = (query, result) -> null;
        final JooqStructuredQueryExecutorExtender coordinator =
                new JooqStructuredQueryExecutorExtender(executor, List.of(returnsNull));

        final QueryResultPage result = coordinator.execute(QUERY);

        assertThat(result).isSameAs(page);
    }

    @Test
    @DisplayName("an extender returning null does not suppress a later extender")
    void nullReturningExtenderDoesNotSuppressLaterExtender() {
        final QueryResultPage page = page();
        when(executor.execute(QUERY)).thenReturn(page);
        final QueryResultPageExtender returnsNull = (query, result) -> null;
        final JooqStructuredQueryExecutorExtender coordinator =
                new JooqStructuredQueryExecutorExtender(executor, List.of(returnsNull, addsKey()));

        final QueryResultPage result = coordinator.execute(QUERY);

        assertThat(result.rows().get(0)).containsEntry("derived", "value");
    }

    private static QueryResultPageExtender addsKey() {
        return (query, result) -> {
            final List<Map<String, Object>> extended = result.rows().stream()
                    .map(row -> {
                        final Map<String, Object> copy = new LinkedHashMap<>(row);
                        copy.put("derived", "value");
                        return copy;
                    })
                    .toList();
            return new QueryResultPage(extended, result.totalCount());
        };
    }

    private static QueryResultPageExtender throwing() {
        return (query, result) -> {
            throw new IllegalStateException("boom");
        };
    }

    private static QueryResultPage page() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "row-1");
        return new QueryResultPage(List.of(row), 1L);
    }
}
