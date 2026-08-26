package com.epam.aidial.evaluation.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryEntityRegistry;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryEntityResolver;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryExecutor;
import com.epam.aidial.evaluation.query.service.translate.QueryParameterResolver;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StructuredQueryServiceTest {

    private final StructuredQueryEntityResolver testSuites = resolver("test_suites");
    private final StructuredQueryEntityResolver evalSummaries = resolver("eval_summaries");
    private final StructuredQueryEntityRegistry entityRegistry =
            new StructuredQueryEntityRegistry(List.of(testSuites, evalSummaries));
    private final StructuredQueryExecutor executor = mock(StructuredQueryExecutor.class);
    private final StructuredQueryService service =
            new StructuredQueryService(executor, entityRegistry, new QueryParameterResolver());

    private static StructuredQueryEntityResolver resolver(String entity) {
        return new StructuredQueryEntityResolver() {
            @Override
            public String entity() {
                return entity;
            }

            @Override
            public DSLContext dsl() {
                throw new UnsupportedOperationException("not exercised by StructuredQueryService dispatch");
            }

            @Override
            public Table<?> table() {
                throw new UnsupportedOperationException("not exercised by StructuredQueryService dispatch");
            }

            @Override
            public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
                throw new UnsupportedOperationException("not exercised by StructuredQueryService dispatch");
            }
        };
    }

    private static StructuredQuery query(String entity) {
        return new StructuredQuery(
                entity, null, QueryMode.ROW, false, null, null, null, null, new OffsetPage(0, 10, false));
    }

    @Test
    @DisplayName("delegates to the executor for a registered entity")
    void delegatesToExecutor() {
        StructuredQuery query = query("eval_summaries");
        QueryResultPage expected = new QueryResultPage(List.of(), null);
        // No params → the resolver returns the same query instance, which is dispatched as-is.
        when(executor.execute(eq(query))).thenReturn(expected);

        QueryResultPage result = service.execute(query);

        assertThat(result).isSameAs(expected);
        verify(executor).execute(eq(query));
    }

    @Test
    @DisplayName("rejects a query for an entity that has no registered resolver")
    void rejectsUnknownEntity() {
        assertThatThrownBy(() -> service.execute(query("datasets")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("datasets")
                .hasMessageContaining("eval_summaries")
                .hasMessageContaining("test_suites");
    }

    @Test
    @DisplayName("rejects a null query")
    void rejectsNullQuery() {
        assertThatThrownBy(() -> service.execute(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("exposes the supported entities in stable order")
    void exposesSupportedEntities() {
        assertThat(service.supportedEntities()).containsExactlyInAnyOrder("test_suites", "eval_summaries");
    }

    @Test
    @DisplayName("fails fast when two resolvers claim the same entity")
    void rejectsDuplicateEntityRegistration() {
        assertThatThrownBy(() ->
                        new StructuredQueryEntityRegistry(List.of(resolver("test_suites"), resolver("test_suites"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test_suites");
    }
}
