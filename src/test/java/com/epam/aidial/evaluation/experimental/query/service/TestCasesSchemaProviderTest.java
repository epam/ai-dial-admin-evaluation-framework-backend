package com.epam.aidial.evaluation.experimental.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TestCasesSchemaProvider")
class TestCasesSchemaProviderTest {

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    private TestCasesSchemaProvider provider;

    private final UUID datasetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        provider = new TestCasesSchemaProvider(datasetSchemaProvider, new JooqTableSchemaResolver());
    }

    @Test
    @DisplayName("descriptor is complex, keyed by dataset_id")
    void descriptor() {
        assertThat(provider.descriptor()).isEqualTo(new QueryEntityDto("test_cases", true, "dataset_id"));
    }

    @Test
    @DisplayName("base schema lists the JSONB data field as-is")
    void baseSchema() {
        assertThat(provider.baseSchema())
                .anySatisfy(field -> assertThat(field.name()).isEqualTo("data"))
                .anySatisfy(field -> assertThat(field.name()).isEqualTo("dataset_id"));
    }

    @Test
    @DisplayName("detailed schema flattens data::<field> from the dataset schema and drops the raw data field")
    void detailedSchemaFlattens() {
        when(datasetSchemaProvider.getSchema(datasetId))
                .thenReturn(List.of(
                        FieldDefinitionDto.builder()
                                .name("category")
                                .type(SchemaFieldType.STRING)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("tags")
                                .type(SchemaFieldType.ARRAY)
                                .build()));

        List<QuerySchemaFieldDto> fields = provider.detailedSchema(Map.of("dataset_id", datasetId.toString()));

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("data::category", QueryFieldType.STRING, "data"),
                        new QuerySchemaFieldDto("data::tags", QueryFieldType.ARRAY, "data"))
                .noneMatch(field -> field.name().equals("data"));
    }

    @Test
    @DisplayName("detailed schema requires the dataset_id parameter")
    void detailedSchemaRequiresDatasetId() {
        assertThatThrownBy(() -> provider.detailedSchema(Map.of())).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("detailed schema rejects a non-UUID dataset_id")
    void detailedSchemaRejectsNonUuid() {
        assertThatThrownBy(() -> provider.detailedSchema(Map.of("dataset_id", "not-a-uuid")))
                .isInstanceOf(ValidationException.class);
    }
}
