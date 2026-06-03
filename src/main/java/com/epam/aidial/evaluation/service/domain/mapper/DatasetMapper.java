package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.service.domain.dto.DatasetReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class DatasetMapper {

    private final JsonbMapper jsonbMapper;
    private final ValidationWarningsSerializer warningsSerializer;

    public DatasetResponseDto toDto(Dataset entity) {
        if (entity == null) {
            return null;
        }
        List<FieldDefinitionDto> testCaseSchema = jsonbMapper.mapFieldDefinitions(entity.getTestCaseSchema());
        List<ValidationWarningDto> validationWarnings =
                warningsSerializer.deserializeWarnings(entity.getValidationWarnings());

        return DatasetResponseDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .testCaseSchema(testCaseSchema)
                .valid(entity.isValid())
                .validationWarnings(validationWarnings)
                .visibility(entity.getVisibility())
                .version(entity.getVersion())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public Dataset toEntity(DatasetRequestDto dto, String createdBy) {
        if (dto == null) {
            return null;
        }
        return Dataset.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .testCaseSchema(jsonbMapper.mapFieldDefinitions(dto.getTestCaseSchema()))
                .valid(true)
                .validationWarnings("[]")
                .visibility(dto.getVisibility())
                .createdBy(createdBy)
                .build();
    }

    public void update(Dataset entity, DatasetRequestDto dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setTestCaseSchema(jsonbMapper.mapFieldDefinitions(dto.getTestCaseSchema()));
    }

    public DatasetReferenceDto toReference(Dataset entity) {
        if (entity == null) {
            return null;
        }
        return DatasetReferenceDto.builder()
                .id(entity.getId())
                .version(entity.getVersion())
                .name(entity.getName())
                .build();
    }
}
