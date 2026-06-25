package com.epam.aidial.evaluation.experimental.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.experimental.query.model.OffsetPage;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.experimental.query.service.repository.StructuredQueryRepository;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StructuredQueryServiceTest {

    private final StructuredQueryRepository testSuites = repository("test_suites");
    private final StructuredQueryRepository evalSummaries = repository("eval_summaries");
    private final StructuredQueryService service = new StructuredQueryService(List.of(testSuites, evalSummaries));

    private static StructuredQueryRepository repository(String entity) {
        StructuredQueryRepository repository = mock(StructuredQueryRepository.class);
        when(repository.supportedEntity()).thenReturn(entity);
        return repository;
    }

    private static StructuredQuery query(String entity) {
        return new StructuredQuery(
                entity, null, QueryMode.ROW, false, null, null, null, null, new OffsetPage(0, 10, false));
    }

    @Test
    @DisplayName("routes a query to the repository that serves its entity")
    void routesByEntity() {
        StructuredQuery query = query("eval_summaries");
        QueryResultPage expected = new QueryResultPage(List.of(), null);
        when(evalSummaries.execute(eq(query), eq(Map.of()))).thenReturn(expected);

        QueryResultPage result = service.execute(query);

        assertThat(result).isSameAs(expected);
        verify(evalSummaries).execute(eq(query), eq(Map.of()));
    }

    @Test
    @DisplayName("rejects a query for an entity that has no registered repository")
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
    @DisplayName("fails fast when two repositories claim the same entity")
    void rejectsDuplicateEntityRegistration() {
        assertThatThrownBy(
                        () -> new StructuredQueryService(List.of(repository("test_suites"), repository("test_suites"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test_suites");
    }
}
