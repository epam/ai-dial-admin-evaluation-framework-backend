package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.REVALIDATION_TASKS;

import com.epam.aidial.evaluation.data.db.mapper.RevalidationTaskRecordMapper;
import com.epam.aidial.evaluation.data.db.model.RevalidationTask;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresRevalidationTaskRepository implements RevalidationTaskRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final RevalidationTaskRecordMapper recordMapper;
    private final TransactionTimestampContext transactionTimestampContext;

    @Override
    public RevalidationTask save(RevalidationTask task) {
        if (task.getId() == null) {
            task.setId(UUID.randomUUID());
        }
        long now = transactionTimestampContext.getTimestamp();
        if (task.getStartedAtMs() == null) {
            task.setStartedAtMs(now);
        }

        dsl.insertInto(REVALIDATION_TASKS)
                .set(REVALIDATION_TASKS.ID, task.getId().toString())
                .set(REVALIDATION_TASKS.DATASET_ID, task.getDatasetId().toString())
                .set(REVALIDATION_TASKS.STATUS, task.getStatus())
                .set(REVALIDATION_TASKS.TOTAL_CASES, task.getTotalCases())
                .set(REVALIDATION_TASKS.PROCESSED_CASES, task.getProcessedCases())
                .set(REVALIDATION_TASKS.VALID_COUNT, task.getValidCount())
                .set(REVALIDATION_TASKS.INVALID_COUNT, task.getInvalidCount())
                .set(REVALIDATION_TASKS.STARTED_AT_MS, task.getStartedAtMs())
                .set(REVALIDATION_TASKS.COMPLETED_AT_MS, task.getCompletedAtMs())
                .set(REVALIDATION_TASKS.ERROR_MESSAGE, task.getErrorMessage())
                .set(
                        REVALIDATION_TASKS.COERCED_CELL_COUNT,
                        task.getCoercedCellCount() != null ? task.getCoercedCellCount() : 0L)
                .execute();
        return task;
    }

    @Override
    public RevalidationTask update(RevalidationTask task) {
        dsl.update(REVALIDATION_TASKS)
                .set(REVALIDATION_TASKS.STATUS, task.getStatus())
                .set(REVALIDATION_TASKS.PROCESSED_CASES, task.getProcessedCases())
                .set(REVALIDATION_TASKS.VALID_COUNT, task.getValidCount())
                .set(REVALIDATION_TASKS.INVALID_COUNT, task.getInvalidCount())
                .set(REVALIDATION_TASKS.COMPLETED_AT_MS, task.getCompletedAtMs())
                .set(REVALIDATION_TASKS.ERROR_MESSAGE, task.getErrorMessage())
                .set(
                        REVALIDATION_TASKS.COERCED_CELL_COUNT,
                        task.getCoercedCellCount() != null ? task.getCoercedCellCount() : 0L)
                .where(REVALIDATION_TASKS.ID.eq(task.getId().toString()))
                .execute();
        return task;
    }

    @Override
    public Optional<RevalidationTask> findById(UUID id) {
        return dsl.selectFrom(REVALIDATION_TASKS)
                .where(REVALIDATION_TASKS.ID.eq(id.toString()))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public Optional<RevalidationTask> findByIdAndDatasetId(UUID id, UUID datasetId) {
        return dsl.selectFrom(REVALIDATION_TASKS)
                .where(REVALIDATION_TASKS
                        .ID
                        .eq(id.toString())
                        .and(REVALIDATION_TASKS.DATASET_ID.eq(datasetId.toString())))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public Page<RevalidationTask> findAllByDatasetId(UUID datasetId, PageRequest pageRequest) {
        int limit = pageRequest.getValidatedSize();
        int offset = pageRequest.getOffset();

        List<RevalidationTask> content = dsl.selectFrom(REVALIDATION_TASKS)
                .where(REVALIDATION_TASKS.DATASET_ID.eq(datasetId.toString()))
                .orderBy(REVALIDATION_TASKS.STARTED_AT_MS.desc().nullsLast())
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);

        Long total = dsl.selectCount()
                .from(REVALIDATION_TASKS)
                .where(REVALIDATION_TASKS.DATASET_ID.eq(datasetId.toString()))
                .fetchOne(0, Long.class);

        return Page.of(content, pageRequest, total != null ? total : 0L);
    }
}
