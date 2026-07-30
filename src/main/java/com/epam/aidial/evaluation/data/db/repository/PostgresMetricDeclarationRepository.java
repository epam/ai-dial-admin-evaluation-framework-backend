package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATIONS;

import com.epam.aidial.evaluation.data.db.mapper.MetricDeclarationRecordMapper;
import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.OrderByBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.PageRequestSqlBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.SortWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresMetricDeclarationRepository implements MetricDeclarationRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final MetricDeclarationRecordMapper recordMapper;
    private final WhereBuilder whereBuilder;
    private final OrderByBuilder orderByBuilder;
    private final TransactionTimestampContext transactionTimestampContext;

    @Override
    public Page<MetricDeclaration> findAll(PageRequest pageRequest) {
        return findAll(pageRequest, List.of(), true);
    }

    @Override
    public Page<MetricDeclaration> findAll(PageRequest pageRequest, boolean includeTotalCount) {
        return findAll(pageRequest, List.of(), includeTotalCount);
    }

    @Override
    public Page<MetricDeclaration> findAll(
            PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }

        Condition condition = whereBuilder.build(filters, FilterWhitelists.METRIC_DECLARATIONS);
        long totalCount = includeTotalCount ? count(condition) : -1;
        List<SortField<?>> orderBy = orderByBuilder.build(pageRequest.getSort(), SortWhitelists.METRIC_DECLARATIONS);

        int limit = PageRequestSqlBuilder.limit(pageRequest);
        long offset = PageRequestSqlBuilder.offset(pageRequest);

        List<MetricDeclaration> content = dsl.selectFrom(METRIC_DECLARATIONS)
                .where(condition)
                .orderBy(orderBy)
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);

        return includeTotalCount ? Page.of(content, pageRequest, totalCount) : Page.withoutTotal(content, pageRequest);
    }

    @Override
    public Optional<MetricDeclaration> findById(UUID id) {
        return dsl.selectFrom(METRIC_DECLARATIONS)
                .where(METRIC_DECLARATIONS.ID.eq(id.toString()))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public Optional<MetricDeclaration> findByProviderIdAndName(String providerId, String name) {
        return dsl.selectFrom(METRIC_DECLARATIONS)
                .where(METRIC_DECLARATIONS.PROVIDER_ID.eq(providerId).and(METRIC_DECLARATIONS.NAME.eq(name)))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public MetricDeclaration save(MetricDeclaration declaration) {
        long now = transactionTimestampContext.getTimestamp();
        if (declaration.getId() == null) {
            declaration.setId(UUID.randomUUID());
        }
        if (declaration.getCreatedAt() == null) {
            declaration.setCreatedAt(now);
        }
        dsl.insertInto(METRIC_DECLARATIONS)
                .set(METRIC_DECLARATIONS.ID, declaration.getId().toString())
                .set(METRIC_DECLARATIONS.PROVIDER_ID, declaration.getProviderId())
                .set(METRIC_DECLARATIONS.NAME, declaration.getName())
                .set(METRIC_DECLARATIONS.DISPLAY_NAME, declaration.getDisplayName())
                .set(METRIC_DECLARATIONS.DESCRIPTION, declaration.getDescription())
                .set(METRIC_DECLARATIONS.CREATED_AT_MS, declaration.getCreatedAt())
                .execute();
        return declaration;
    }

    @Override
    public void updateMetadata(UUID id, String description, String displayName) {
        dsl.update(METRIC_DECLARATIONS)
                .set(METRIC_DECLARATIONS.DESCRIPTION, description)
                .set(METRIC_DECLARATIONS.DISPLAY_NAME, displayName)
                .where(METRIC_DECLARATIONS.ID.eq(id.toString()))
                .execute();
    }

    private long count(Condition condition) {
        Long count =
                dsl.selectCount().from(METRIC_DECLARATIONS).where(condition).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }
}
