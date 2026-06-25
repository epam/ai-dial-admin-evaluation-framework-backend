package com.epam.aidial.evaluation.experimental.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntitySchemaDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryEntityRegistryTest {

    private static final QuerySchemaFieldDto SUITE_NAME_FIELD =
            new QuerySchemaFieldDto("name", QueryFieldType.STRING, "name");
    private static final QuerySchemaFieldDto METRIC_FIELD =
            new QuerySchemaFieldDto("metric::Accuracy::score", QueryFieldType.DECIMAL, "metricValues");

    private final QueryEntityRegistry registry = new QueryEntityRegistry(List.of(simpleProvider(), complexProvider()));

    @Test
    @DisplayName("lists registered entities in stable alphabetical order")
    void shouldListEntitiesInAlphabeticalOrder() {
        List<QueryEntityDto> entities = registry.listEntities();

        assertThat(entities)
                .containsExactly(
                        new QueryEntityDto("eval_summaries", true, "test_suite_run_id"),
                        new QueryEntityDto("test_suites", false, null));
    }

    @Test
    @DisplayName("returns the base schema with the entity descriptor for a known entity")
    void shouldReturnBaseSchema_whenEntityKnown() {
        QueryEntitySchemaDto schema = registry.getBaseSchema("test_suites");

        assertThat(schema).isEqualTo(new QueryEntitySchemaDto("test_suites", false, null, List.of(SUITE_NAME_FIELD)));
    }

    @Test
    @DisplayName("throws EntityNotFoundException for an unknown entity name")
    void shouldThrowNotFound_whenEntityUnknown() {
        assertThatThrownBy(() -> registry.getBaseSchema("unknown_entity"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("unknown_entity");
    }

    @Test
    @DisplayName("delegates the detailed schema to the provider for a complex entity")
    void shouldReturnDetailedSchema_whenEntityComplex() {
        QueryEntitySchemaDto schema =
                registry.getDetailedSchema("eval_summaries", Map.of("test_suite_run_id", "run-1"));

        assertThat(schema)
                .isEqualTo(
                        new QueryEntitySchemaDto("eval_summaries", true, "test_suite_run_id", List.of(METRIC_FIELD)));
    }

    @Test
    @DisplayName("throws ValidationException when a detailed schema is requested for a simple entity")
    void shouldThrowValidation_whenDetailedSchemaRequestedForSimpleEntity() {
        assertThatThrownBy(() -> registry.getDetailedSchema("test_suites", Map.of("test_suite_id", "any-id")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("test_suites");
    }

    @Test
    @DisplayName("rejects duplicate entity registrations at construction time")
    void shouldThrowIllegalState_whenDuplicateEntityRegistered() {
        assertThatThrownBy(() -> new QueryEntityRegistry(List.of(simpleProvider(), simpleProvider())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test_suites");
    }

    private static QueryableEntitySchemaProvider simpleProvider() {
        return new QueryableEntitySchemaProvider() {
            @Override
            public QueryEntityDto descriptor() {
                return new QueryEntityDto("test_suites", false, null);
            }

            @Override
            public List<QuerySchemaFieldDto> baseSchema() {
                return List.of(SUITE_NAME_FIELD);
            }
        };
    }

    private static QueryableEntitySchemaProvider complexProvider() {
        return new QueryableEntitySchemaProvider() {
            @Override
            public QueryEntityDto descriptor() {
                return new QueryEntityDto("eval_summaries", true, "test_suite_run_id");
            }

            @Override
            public List<QuerySchemaFieldDto> baseSchema() {
                return List.of();
            }

            @Override
            public List<QuerySchemaFieldDto> detailedSchema(Map<String, String> params) {
                return List.of(METRIC_FIELD);
            }
        };
    }
}
