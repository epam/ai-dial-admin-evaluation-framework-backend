package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.DATASETS;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.exception.OptimisticLockException;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.DatasetsRecord;
import com.epam.aidial.evaluation.data.db.mapper.DatasetRecordMapper;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.OrderByBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.PageRequestSqlBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.SortWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresDatasetRepository implements DatasetRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final DatasetRecordMapper recordMapper;
    private final TransactionTimestampContext transactionTimestampContext;
    private final WhereBuilder whereBuilder;
    private final OrderByBuilder orderByBuilder;

    @Override
    public Page<Dataset> findAll(PageRequest pageRequest) {
        return findAll(pageRequest, List.of(), true);
    }

    @Override
    public Page<Dataset> findAll(PageRequest pageRequest, boolean includeTotalCount) {
        return findAll(pageRequest, List.of(), includeTotalCount);
    }

    @Override
    public Page<Dataset> findAll(PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }

        // Catalogue list hard-filters PRIVATE datasets out — they are reachable only
        // via findById. Visibility is not in FilterWhitelists.DATASETS, so user filters
        // cannot override this predicate.
        Condition condition = whereBuilder
                .build(filters, FilterWhitelists.DATASETS)
                .and(DATASETS.VISIBILITY.eq(DatasetVisibility.PUBLIC.getValue()));
        long totalCount = includeTotalCount ? count(condition) : -1;
        List<SortField<?>> orderBy = orderByBuilder.build(pageRequest.getSort(), SortWhitelists.DATASETS);

        int limit = PageRequestSqlBuilder.limit(pageRequest);
        long offset = PageRequestSqlBuilder.offset(pageRequest);

        List<Dataset> content = dsl.selectFrom(DATASETS)
                .where(condition)
                .orderBy(orderBy)
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);

        return includeTotalCount ? Page.of(content, pageRequest, totalCount) : Page.withoutTotal(content, pageRequest);
    }

    @Override
    public Optional<Dataset> findById(UUID id) {
        return dsl.selectFrom(DATASETS).where(DATASETS.ID.eq(id.toString())).fetchOptional(recordMapper::map);
    }

    @Override
    public Optional<Dataset> findByIdForUpdate(UUID id) {
        return dsl.selectFrom(DATASETS)
                .where(DATASETS.ID.eq(id.toString()))
                .forUpdate()
                .fetchOptional(recordMapper::map);
    }

    @Override
    public Dataset save(Dataset dataset) {
        boolean isNew = dataset.getId() == null;
        return isNew ? create(dataset) : update(dataset);
    }

    private Dataset create(Dataset dataset) {
        if (dataset.getVisibility() == null) {
            throw new IllegalArgumentException("Dataset visibility is required");
        }
        long now = transactionTimestampContext.getTimestamp();
        dataset.setId(UUID.randomUUID());
        if (dataset.getVersion() == null) {
            dataset.setVersion(0L);
        }
        dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);

        dsl.insertInto(DATASETS)
                .set(DATASETS.ID, dataset.getId().toString())
                .set(DATASETS.NAME, dataset.getName())
                .set(DATASETS.DESCRIPTION, dataset.getDescription())
                .set(DATASETS.TEST_CASE_SCHEMA, toJsonb(dataset.getTestCaseSchema()))
                .set(DATASETS.IS_VALID, dataset.isValid())
                .set(DATASETS.VALIDATION_WARNINGS, toJsonb(dataset.getValidationWarnings()))
                .set(DATASETS.VISIBILITY, dataset.getVisibility().getValue())
                .set(DATASETS.VERSION, dataset.getVersion())
                .set(DATASETS.CREATED_BY, dataset.getCreatedBy())
                .set(DATASETS.CREATED_AT_MS, dataset.getCreatedAt())
                .set(DATASETS.UPDATED_AT_MS, dataset.getUpdatedAt())
                .execute();
        log.debug("Created Dataset with id: {}", dataset.getId());
        return dataset;
    }

    private Dataset update(Dataset dataset) {
        long now = transactionTimestampContext.getTimestamp();
        Long version = dataset.getVersion();
        if (version == null) {
            throw new IllegalArgumentException("Version is required for update (optimistic locking)");
        }

        DatasetsRecord updated = dsl.update(DATASETS)
                .set(DATASETS.NAME, dataset.getName())
                .set(DATASETS.DESCRIPTION, dataset.getDescription())
                .set(DATASETS.TEST_CASE_SCHEMA, toJsonb(dataset.getTestCaseSchema()))
                .set(DATASETS.IS_VALID, dataset.isValid())
                .set(DATASETS.VALIDATION_WARNINGS, toJsonb(dataset.getValidationWarnings()))
                .set(DATASETS.VERSION, DATASETS.VERSION.add(1))
                .set(DATASETS.UPDATED_AT_MS, now)
                .where(DATASETS.ID.eq(dataset.getId().toString()).and(DATASETS.VERSION.eq(version)))
                .returning()
                .fetchOne();

        if (updated == null) {
            throw new OptimisticLockException(
                    "Dataset version conflict: expected version " + version + " for id " + dataset.getId());
        }
        log.debug("Updated Dataset with id: {}", dataset.getId());
        return recordMapper.map(updated);
    }

    @Override
    public long count() {
        Long count = dsl.selectCount().from(DATASETS).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    private long count(Condition condition) {
        Long count = dsl.selectCount().from(DATASETS).where(condition).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public boolean deleteById(UUID id) {
        // DB FK: test_suites.dataset_id ON DELETE RESTRICT — a dependent suite causes
        // a DataIntegrityViolation that DatasetService translates to HTTP 409.
        int deleted =
                dsl.deleteFrom(DATASETS).where(DATASETS.ID.eq(id.toString())).execute();
        log.debug("Deleted Dataset with id: {}, rows affected: {}", id, deleted);
        return deleted > 0;
    }

    @Override
    public boolean existsById(UUID id) {
        return dsl.fetchExists(DATASETS, DATASETS.ID.eq(id.toString()));
    }

    @Override
    public boolean existsByNameIgnoreCase(String name) {
        // Matches the uq_datasets_name unique index on LOWER(name) so the dedup pre-check
        // sees the same collisions the index would enforce.
        return dsl.fetchExists(DATASETS, DSL.lower(DATASETS.NAME).eq(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public void updateIsValid(UUID id, boolean isValid) {
        dsl.update(DATASETS)
                .set(DATASETS.IS_VALID, isValid)
                .where(DATASETS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public void updateTestCaseSchema(UUID id, String schemaJson) {
        long now = transactionTimestampContext.getTimestamp();
        dsl.update(DATASETS)
                .set(DATASETS.TEST_CASE_SCHEMA, toJsonb(schemaJson))
                .set(DATASETS.VERSION, DATASETS.VERSION.add(1))
                .set(DATASETS.UPDATED_AT_MS, now)
                .where(DATASETS.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public Dataset createWithId(Dataset dataset, long timestamp) {
        if (dataset.getVisibility() == null) {
            throw new IllegalArgumentException("Dataset visibility is required");
        }
        dataset.setCreatedAt(timestamp);
        dataset.setUpdatedAt(timestamp);
        if (dataset.getVersion() == null) {
            dataset.setVersion(0L);
        }

        dsl.insertInto(DATASETS)
                .set(DATASETS.ID, dataset.getId().toString())
                .set(DATASETS.NAME, dataset.getName())
                .set(DATASETS.DESCRIPTION, dataset.getDescription())
                .set(DATASETS.TEST_CASE_SCHEMA, toJsonb(dataset.getTestCaseSchema()))
                .set(DATASETS.IS_VALID, dataset.isValid())
                .set(DATASETS.VALIDATION_WARNINGS, toJsonb(dataset.getValidationWarnings()))
                .set(DATASETS.VISIBILITY, dataset.getVisibility().getValue())
                .set(DATASETS.VERSION, dataset.getVersion())
                .set(DATASETS.CREATED_BY, dataset.getCreatedBy())
                .set(DATASETS.CREATED_AT_MS, timestamp)
                .set(DATASETS.UPDATED_AT_MS, timestamp)
                .execute();
        log.debug("Created Dataset with supplied id: {}", dataset.getId());
        return dataset;
    }

    @Override
    public void updateVisibility(UUID id, DatasetVisibility visibility, long updatedAt) {
        dsl.update(DATASETS)
                .set(DATASETS.VISIBILITY, visibility.getValue())
                .set(DATASETS.VERSION, DATASETS.VERSION.add(1))
                .set(DATASETS.UPDATED_AT_MS, updatedAt)
                .where(DATASETS.ID.eq(id.toString()))
                .execute();
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
