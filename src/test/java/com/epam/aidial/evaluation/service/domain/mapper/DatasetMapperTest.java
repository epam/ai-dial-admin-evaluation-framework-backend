package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.service.domain.dto.DatasetReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DatasetMapper")
class DatasetMapperTest {

    private DatasetMapper mapper;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonbMapper jsonbMapper = new JsonbMapper(objectMapper);
        ValidationWarningsSerializer warningsSerializer = new ValidationWarningsSerializer(objectMapper);
        mapper = new DatasetMapper(jsonbMapper, warningsSerializer);
    }

    @Test
    @DisplayName("toDto deserializes schema JSON to typed field list and warnings to typed list")
    void toDtoMapsAllFields() {
        UUID id = UUID.randomUUID();
        Dataset entity = Dataset.builder()
                .id(id)
                .name("My Dataset")
                .description("desc")
                .testCaseSchema("[{\"name\":\"q\",\"type\":\"STRING\",\"required\":true}]")
                .valid(true)
                .validationWarnings("[]")
                .version(3L)
                .createdBy("u@example.com")
                .createdAt(100L)
                .updatedAt(200L)
                .build();

        DatasetResponseDto dto = mapper.toDto(entity);

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getName()).isEqualTo("My Dataset");
        assertThat(dto.getDescription()).isEqualTo("desc");
        assertThat(dto.getTestCaseSchema()).hasSize(1);
        assertThat(dto.getTestCaseSchema().get(0).getName()).isEqualTo("q");
        assertThat(dto.getTestCaseSchema().get(0).getType()).isEqualTo(SchemaFieldType.STRING);
        assertThat(dto.getTestCaseSchema().get(0).isRequired()).isTrue();
        assertThat(dto.isValid()).isTrue();
        assertThat(dto.getValidationWarnings()).isEmpty();
        assertThat(dto.getVersion()).isEqualTo(3L);
        assertThat(dto.getCreatedBy()).isEqualTo("u@example.com");
        assertThat(dto.getCreatedAt()).isEqualTo(100L);
        assertThat(dto.getUpdatedAt()).isEqualTo(200L);
    }

    @Test
    @DisplayName("toDto returns null when entity is null")
    void toDtoReturnsNullForNullEntity() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    @DisplayName("toEntity serializes typed schema to JSON and defaults valid=true with empty warnings")
    void toEntityMapsRequestToFreshEntity() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("New Dataset")
                .description("d")
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("q")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        Dataset entity = mapper.toEntity(request, "creator@example.com");

        assertThat(entity.getName()).isEqualTo("New Dataset");
        assertThat(entity.getDescription()).isEqualTo("d");
        assertThat(entity.getTestCaseSchema()).contains("\"name\":\"q\"");
        assertThat(entity.isValid()).isTrue();
        assertThat(entity.getValidationWarnings()).isEqualTo("[]");
        assertThat(entity.getCreatedBy()).isEqualTo("creator@example.com");
    }

    @Test
    @DisplayName("toEntity returns null when dto is null")
    void toEntityReturnsNullForNullDto() {
        assertThat(mapper.toEntity(null, "x")).isNull();
    }

    @Test
    @DisplayName("update mutates existing entity's name, description, and schema fields only")
    void updateOverwritesMutableFields() {
        Dataset existing = Dataset.builder()
                .id(UUID.randomUUID())
                .name("Old")
                .description("old desc")
                .testCaseSchema("[]")
                .valid(false)
                .validationWarnings("[{\"path\":\"x\",\"message\":\"old\"}]")
                .version(5L)
                .createdBy("orig@example.com")
                .createdAt(1L)
                .updatedAt(2L)
                .build();

        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("New")
                .description("new desc")
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("answer")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        mapper.update(existing, request);

        assertThat(existing.getName()).isEqualTo("New");
        assertThat(existing.getDescription()).isEqualTo("new desc");
        assertThat(existing.getTestCaseSchema()).contains("\"name\":\"answer\"");
        assertThat(existing.getVersion()).isEqualTo(5L);
        assertThat(existing.getCreatedBy()).isEqualTo("orig@example.com");
        assertThat(existing.isValid()).isFalse();
        assertThat(existing.getValidationWarnings()).isEqualTo("[{\"path\":\"x\",\"message\":\"old\"}]");
    }

    @Test
    @DisplayName("update is a no-op when entity or dto is null")
    void updateNoOpForNullInputs() {
        Dataset existing = Dataset.builder().name("keep").build();
        mapper.update(existing, null);
        assertThat(existing.getName()).isEqualTo("keep");
        mapper.update(null, DatasetRequestDto.builder().name("ignored").build());
    }

    @Test
    @DisplayName("toReference carries id, version, and name only")
    void toReferenceCarriesSummaryFieldsOnly() {
        UUID id = UUID.randomUUID();
        Dataset entity = Dataset.builder()
                .id(id)
                .name("My Dataset")
                .version(9L)
                .description("ignored")
                .testCaseSchema("[]")
                .createdBy("ignored")
                .build();

        DatasetReferenceDto ref = mapper.toReference(entity);

        assertThat(ref.getId()).isEqualTo(id);
        assertThat(ref.getVersion()).isEqualTo(9L);
        assertThat(ref.getName()).isEqualTo("My Dataset");
    }

    @Test
    @DisplayName("toReference returns null when entity is null")
    void toReferenceReturnsNullForNullEntity() {
        assertThat(mapper.toReference(null)).isNull();
    }

    @Test
    @DisplayName("toDto maps visibility from entity to response DTO")
    void toDtoMapsVisibility() {
        Dataset entity = Dataset.builder()
                .id(UUID.randomUUID())
                .name("D")
                .visibility(DatasetVisibility.PRIVATE)
                .validationWarnings("[]")
                .build();

        DatasetResponseDto dto = mapper.toDto(entity);

        assertThat(dto.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
    }

    @Test
    @DisplayName("toEntity maps visibility from request DTO to entity for the create path")
    void toEntityMapsVisibilityForCreate() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("New")
                .visibility(DatasetVisibility.PRIVATE)
                .build();

        Dataset entity = mapper.toEntity(request, "creator@example.com");

        assertThat(entity.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
    }

    @Test
    @DisplayName("update does NOT propagate visibility — PUT silently ignores the field")
    void updateIgnoresVisibilityField() {
        Dataset existing = Dataset.builder()
                .id(UUID.randomUUID())
                .name("D")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .build();
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("D")
                .visibility(DatasetVisibility.PRIVATE) // should be ignored
                .build();

        mapper.update(existing, request);

        assertThat(existing.getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
    }
}
