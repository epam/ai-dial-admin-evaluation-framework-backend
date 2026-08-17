package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.util.TestCaseTurnsCsvSerializer;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBatchPutItemDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseMapper {

    private final ValidationWarningsSerializer warningsSerializer;
    private final TestCaseTurnsCsvSerializer turnsCsvSerializer;

    public TestCaseResponseDto toDto(TestCase entity, boolean includeWarnings) {
        if (entity == null) {
            return null;
        }
        return TestCaseResponseDto.builder()
                .id(entity.getId())
                .testCaseName(entity.getTestCaseName())
                .data(warningsSerializer.deserializeMap(entity.getData()))
                .multiTurnData(turnsCsvSerializer.deserializeTurns(entity.getMultiTurnData()))
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
        // data and multiTurnData are carried through verbatim; mutual exclusivity (both non-empty together)
        // is enforced as a 400 by MultiTurnFieldsValidator, not silently resolved here. A legit multi-turn
        // create omits data (defaults to an empty map → "{}"), satisfying the DB CHECK.
        return TestCase.builder()
                .datasetId(datasetId)
                .testCaseName(dto.getTestCaseName())
                .data(warningsSerializer.serializeMap(dto.getData()))
                .multiTurnData(turnsCsvSerializer.serializeTurns(dto.getMultiTurnData()))
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
        entity.setMultiTurnData(turnsCsvSerializer.serializeTurns(dto.getMultiTurnData()));
    }

    public void updateEntity(TestCase entity, TestCaseBatchPutItemDto dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setTestCaseName(dto.getTestCaseName());
        entity.setData(warningsSerializer.serializeMap(dto.getData()));
        entity.setMultiTurnData(turnsCsvSerializer.serializeTurns(dto.getMultiTurnData()));
    }
}
