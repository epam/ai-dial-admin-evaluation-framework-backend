package com.epam.aidial.evaluation.experimental.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestSuitesSchemaProviderTest {

    private final TestSuitesSchemaProvider provider = new TestSuitesSchemaProvider(new JooqTableSchemaResolver());

    @Test
    @DisplayName("describes test_suites as a simple entity without a schema id field")
    void shouldDescribeSimpleEntity() {
        assertThat(provider.descriptor()).isEqualTo(new QueryEntityDto("test_suites", false, null));
    }

    @Test
    @DisplayName("exposes a flat base schema where every field is backed by itself")
    void shouldExposeFlatBaseSchemaWithSelfSources() {
        assertThat(provider.baseSchema())
                .isNotEmpty()
                .allSatisfy(field -> assertThat(field.source()).isEqualTo(field.name()))
                .contains(
                        new QuerySchemaFieldDto("id", QueryFieldType.UUID, "id"),
                        new QuerySchemaFieldDto("suite_type", QueryFieldType.STRING, "suite_type"),
                        new QuerySchemaFieldDto("dataset_id", QueryFieldType.UUID, "dataset_id"),
                        new QuerySchemaFieldDto("response_columns", QueryFieldType.ARRAY, "response_columns"),
                        new QuerySchemaFieldDto("is_valid", QueryFieldType.BOOLEAN, "is_valid"),
                        new QuerySchemaFieldDto("created_at_ms", QueryFieldType.LONG, "created_at_ms"));
    }

    @Test
    @DisplayName("has no detailed schema, being a simple entity")
    void shouldThrowUnsupported_whenDetailedSchemaRequested() {
        assertThatThrownBy(() -> provider.detailedSchema(Map.of("test_suite_id", "any-id")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
