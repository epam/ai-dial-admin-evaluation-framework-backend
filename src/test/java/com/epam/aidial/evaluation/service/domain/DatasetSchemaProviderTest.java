package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DatasetSchemaProvider")
@ExtendWith(MockitoExtension.class)
class DatasetSchemaProviderTest {

    @Mock
    private DatasetRepository datasetRepository;

    private DatasetSchemaProvider provider;

    @BeforeEach
    void setUp() {
        JsonbMapper jsonbMapper = new JsonbMapper(new ObjectMapper());
        provider = new DatasetSchemaProvider(datasetRepository, jsonbMapper);
    }

    @Test
    @DisplayName("returns parsed field definitions when dataset exists with non-empty schema")
    void returnsParsedSchemaWhenDatasetExists() {
        UUID datasetId = UUID.randomUUID();
        String schemaJson = "[{\"name\":\"question\",\"type\":\"STRING\",\"required\":true}]";
        Dataset dataset =
                Dataset.builder().id(datasetId).testCaseSchema(schemaJson).build();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        List<FieldDefinitionDto> result = provider.getSchema(datasetId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("question");
        assertThat(result.get(0).getType()).isEqualTo(SchemaFieldType.STRING);
        assertThat(result.get(0).isRequired()).isTrue();
    }

    @Test
    @DisplayName("returns empty list when dataset exists but schema column is null")
    void returnsEmptyListWhenSchemaIsNull() {
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = Dataset.builder().id(datasetId).testCaseSchema(null).build();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        List<FieldDefinitionDto> result = provider.getSchema(datasetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns empty list when dataset exists with empty JSON array schema")
    void returnsEmptyListWhenSchemaIsEmptyArray() {
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = Dataset.builder().id(datasetId).testCaseSchema("[]").build();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        List<FieldDefinitionDto> result = provider.getSchema(datasetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("throws EntityNotFoundException when dataset id does not exist")
    void throwsWhenDatasetMissing() {
        UUID datasetId = UUID.randomUUID();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.getSchema(datasetId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(datasetId.toString());
    }
}
