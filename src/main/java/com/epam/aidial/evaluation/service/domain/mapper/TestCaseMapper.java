package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBatchPutItemDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseMapper {

    private final ValidationWarningsSerializer warningsSerializer;

    public TestCaseResponseDto toDto(TestCase entity, boolean includeWarnings) {
        if (entity == null) {
            return null;
        }
        return TestCaseResponseDto.builder()
                .id(entity.getId())
                .testCaseName(entity.getTestCaseName())
                .data(warningsSerializer.deserializeMap(entity.getData()))
                .multiTurnId(entity.getMultiTurnId())
                .turnIndex(entity.getTurnIndex())
                .valid(entity.isValid())
                .validationWarnings(
                        includeWarnings ? warningsSerializer.deserializeWarnings(entity.getValidationWarnings()) : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public TestCase toEntity(TestCaseRequestDto dto, UUID datasetId) {
        if (dto == null) {
            return null;
        }
        return TestCase.builder()
                .datasetId(datasetId)
                .testCaseName(dto.getTestCaseName())
                .data(warningsSerializer.serializeMap(dto.getData()))
                .multiTurnId(dto.getMultiTurnId())
                .turnIndex(dto.getTurnIndex())
                .valid(false)
                .validationWarnings("[]")
                .build();
    }

    public void updateEntity(TestCase entity, TestCaseRequestDto dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setTestCaseName(dto.getTestCaseName());
        entity.setData(warningsSerializer.serializeMap(dto.getData()));
        entity.setMultiTurnId(dto.getMultiTurnId());
        entity.setTurnIndex(dto.getTurnIndex());
    }

    public void updateEntity(TestCase entity, TestCaseBatchPutItemDto dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setTestCaseName(dto.getTestCaseName());
        entity.setData(warningsSerializer.serializeMap(dto.getData()));
        entity.setMultiTurnId(dto.getMultiTurnId());
        entity.setTurnIndex(dto.getTurnIndex());
    }
}
