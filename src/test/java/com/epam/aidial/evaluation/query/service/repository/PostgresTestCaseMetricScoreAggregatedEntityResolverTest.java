package com.epam.aidial.evaluation.query.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.TestCaseMetricScoresSchemaProvider;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostgresTestCaseMetricScoreAggregatedEntityResolver")
class PostgresTestCaseMetricScoreAggregatedEntityResolverTest {

    private static final String LATEST_FIELD = "is_latest_computation";

    private final JooqTableSchemaResolver schemaResolver = new JooqTableSchemaResolver();
    private final PostgresTestCaseMetricScoreAggregatedEntityResolver resolver =
            new PostgresTestCaseMetricScoreAggregatedEntityResolver(
                    mock(DSLContext.class),
                    schemaResolver,
                    mock(MetricScoreLatestComputationDefaulter.class),
                    new LatestComputationSupport());

    @Test
    @DisplayName("bindings contain the internal BOOLEAN is_latest_computation field")
    void bindingsContainLatestComputationField() {
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));

        assertThat(bindings).containsKey(LATEST_FIELD);
        assertThat(bindings.get(LATEST_FIELD).type()).isEqualTo(QueryFieldType.BOOLEAN);
    }

    @Test
    @DisplayName("is_latest_computation is not a column of the entity table")
    void tableDoesNotProjectLatestComputationField() {
        assertThat(resolver.table().fields()).extracting(Field::getName).doesNotContain(LATEST_FIELD);
    }

    @Test
    @DisplayName("is_latest_computation is not part of the published schema")
    void publishedSchemaDoesNotContainLatestComputationField() {
        final TestCaseMetricScoresSchemaProvider provider = new TestCaseMetricScoresSchemaProvider(schemaResolver);

        assertThat(provider.baseSchema()).extracting(QuerySchemaFieldDto::name).doesNotContain(LATEST_FIELD);
    }
}
