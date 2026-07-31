package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the typed test-case schema of a dataset by id. Centralises the dataset-rooted
 * schema lookup so callers (revalidation phase 2, snapshot phase, template-variable service,
 * etc.) don't have to repeat the {@code DatasetRepository.findById → JsonbMapper.mapFieldDefinitions}
 * sequence inline.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class DatasetSchemaProvider {

    private final DatasetRepository datasetRepository;
    private final JsonbMapper jsonbMapper;

    /**
     * Returns the dataset's typed test-case schema. Throws {@link EntityNotFoundException}
     * if the dataset does not exist. Returns an empty list if the dataset exists but its
     * schema column is empty/null.
     */
    public List<FieldDefinitionDto> getSchema(UUID datasetId) {
        Dataset dataset = datasetRepository
                .findById(datasetId)
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + datasetId));
        List<FieldDefinitionDto> schema = jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema());
        return schema != null ? schema : List.of();
    }
}
