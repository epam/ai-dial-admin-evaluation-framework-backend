package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.RevalidationTasksRecord;
import com.epam.aidial.evaluation.data.db.model.RevalidationTask;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class RevalidationTaskRecordMapper {

    public RevalidationTask map(RevalidationTasksRecord r) {
        return RevalidationTask.builder()
                .id(UUID.fromString(r.getId()))
                .datasetId(UUID.fromString(r.getDatasetId()))
                .status(r.getStatus())
                .totalCases(r.getTotalCases())
                .processedCases(r.getProcessedCases())
                .validCount(r.getValidCount())
                .invalidCount(r.getInvalidCount())
                .startedAtMs(r.getStartedAtMs())
                .completedAtMs(r.getCompletedAtMs())
                .errorMessage(r.getErrorMessage())
                .coercedCellCount(r.getCoercedCellCount())
                .build();
    }
}
