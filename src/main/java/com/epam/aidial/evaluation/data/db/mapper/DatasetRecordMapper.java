package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.DatasetsRecord;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class DatasetRecordMapper {

    public Dataset map(DatasetsRecord r) {
        return Dataset.builder()
                .id(UUID.fromString(r.getId()))
                .name(r.getName())
                .description(r.getDescription())
                .testCaseSchema(toJsonString(r.getTestCaseSchema()))
                .valid(r.getIsValid())
                .validationWarnings(toJsonString(r.getValidationWarnings()))
                .visibility(DatasetVisibility.fromValue(r.getVisibility()))
                .version(r.getVersion())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAtMs())
                .updatedAt(r.getUpdatedAtMs())
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
