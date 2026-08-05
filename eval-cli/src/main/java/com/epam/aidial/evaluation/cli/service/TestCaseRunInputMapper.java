package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * MapStruct mapper: {@link TestCaseResponseDto} → {@link TestCaseRunInput}.
 *
 * <p>The {@code runId} and {@code position} fields are not present in the DTO and must be set by the
 * caller after mapping (they are run-scoped, not test-case-scoped).
 */
@Mapper(componentModel = "spring", uses = TestCaseRunInputMapper.JsonSerializer.class)
public interface TestCaseRunInputMapper {

    @Mapping(target = "testCaseId", source = "id")
    @Mapping(target = "runId", ignore = true)
    @Mapping(target = "position", ignore = true)
    @Mapping(target = "requestTemplateOverride", ignore = true)
    @Mapping(target = "inputBindingsOverride", ignore = true)
    @Mapping(target = "testCaseData", source = "data", qualifiedByName = "mapToJson")
    @Mapping(target = "multiTurnData", source = "multiTurnData", qualifiedByName = "mapToJson")
    TestCaseRunInput toInput(TestCaseResponseDto dto);

    /** Helper component used by MapStruct to serialize {@code Map} / {@code List} fields to JSON strings. */
    @LogExecution
    @org.springframework.stereotype.Component
    class JsonSerializer {

        private final ObjectMapper objectMapper;

        public JsonSerializer(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Named("mapToJson")
        public String mapToJson(Object value) {
            if (value == null) {
                return null;
            }
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JacksonException e) {
                throw new RuntimeException("Failed to serialize test-case field to JSON: " + e.getMessage(), e);
            }
        }
    }
}
