package com.epam.aidial.evaluation.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TestCaseFieldBindingsBuilder")
class TestCaseFieldBindingsBuilderTest {

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    private TestCaseFieldBindingsBuilder builder;

    private final UUID datasetId = UUID.randomUUID();

    private static FieldDefinitionDto field(String name, SchemaFieldType type) {
        return FieldDefinitionDto.builder().name(name).type(type).build();
    }

    private Map<String, QueryFieldBinding> buildFor(List<FieldDefinitionDto> schema) {
        builder = new TestCaseFieldBindingsBuilder(
                new JooqTableSchemaResolver(),
                new PostgresJsonPathAccessor(),
                datasetSchemaProvider,
                new SchemaFieldTypeMapper());
        when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);
        return builder.build(datasetId);
    }

    @Test
    @DisplayName("includes base columns plus type-aware flattened data::<field> bindings")
    void includesBaseAndFlattenedBindings() {
        Map<String, QueryFieldBinding> bindings =
                buildFor(List.of(field("category", SchemaFieldType.STRING), field("tags", SchemaFieldType.ARRAY)));

        // base columns from the generated table
        assertThat(bindings).containsKeys("id", "dataset_id", "is_valid", "data");
        assertThat(bindings.get("dataset_id").type()).isEqualTo(QueryFieldType.UUID);

        // flattened data::<field> entries carry the dataset-declared type
        assertThat(bindings.get("data::category").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("data::tags").type()).isEqualTo(QueryFieldType.ARRAY);
    }
}
