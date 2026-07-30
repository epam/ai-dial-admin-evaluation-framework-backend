package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATIONS;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_DECLARATION_VERSIONS;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_METRIC_DEFINITIONS;

import com.epam.aidial.evaluation.data.db.mapper.AggregatedMetricDefinitionRowMapper;
import com.epam.aidial.evaluation.data.db.mapper.TestSuiteMetricDefinitionRecordMapper;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.SortField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestSuiteMetricDefinitionRepository implements TestSuiteMetricDefinitionRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final TestSuiteMetricDefinitionRecordMapper recordMapper;
    private final AggregatedMetricDefinitionRowMapper aggregatedRowMapper;
    private final TransactionTimestampContext transactionTimestampContext;
    private final WhereBuilder whereBuilder;
    private final OrderByBuilder orderByBuilder;

    @Override
    public TestSuiteMetricDefinition save(TestSuiteMetricDefinition entity) {
        long now = transactionTimestampContext.getTimestamp();
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        dsl.insertInto(TEST_SUITE_METRIC_DEFINITIONS)
                .set(TEST_SUITE_METRIC_DEFINITIONS.ID, entity.getId().toString())
                .set(
                        TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID,
                        entity.getTestSuiteId().toString())
                .set(
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                        entity.getMetricDeclarationId().toString())
                .set(
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                        entity.getMetricDeclarationVersionId().toString())
                .set(TEST_SUITE_METRIC_DEFINITIONS.NAME, entity.getName())
                .set(TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS, toJsonb(entity.getConfigBindings()))
                .set(TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS, toJsonb(entity.getInputBindings()))
                .set(TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED, entity.isEnabled())
                .set(TEST_SUITE_METRIC_DEFINITIONS.CONDITION, entity.getCondition())
                .set(TEST_SUITE_METRIC_DEFINITIONS.IS_VALID, entity.isValid())
                .set(TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS, toJsonb(entity.getValidationWarnings()))
                .set(TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS, entity.getCreatedAt())
                .set(TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS, entity.getUpdatedAt())
                .execute();
        log.debug("Created TestSuiteMetricDefinition with id: {}", entity.getId());
        return entity;
    }

    @Override
    public Optional<TestSuiteMetricDefinition> findById(UUID id) {
        return dsl.select(
                        TEST_SUITE_METRIC_DEFINITIONS.ID,
                        TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.NAME,
                        TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED,
                        TEST_SUITE_METRIC_DEFINITIONS.CONDITION,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_VALID,
                        TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS,
                        TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS,
                        METRIC_DECLARATIONS.NAME.as("metric_declaration_name"))
                .from(TEST_SUITE_METRIC_DEFINITIONS)
                .join(METRIC_DECLARATIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID.eq(METRIC_DECLARATIONS.ID))
                .where(TEST_SUITE_METRIC_DEFINITIONS.ID.eq(id.toString()))
                .fetchOptional(this::mapWithDeclarationName);
    }

    @Override
    public Optional<TestSuiteMetricDefinition> findByIdAndTestSuiteId(UUID id, UUID testSuiteId) {
        return dsl.select(
                        TEST_SUITE_METRIC_DEFINITIONS.ID,
                        TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.NAME,
                        TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED,
                        TEST_SUITE_METRIC_DEFINITIONS.CONDITION,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_VALID,
                        TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS,
                        TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS,
                        METRIC_DECLARATIONS.NAME.as("metric_declaration_name"))
                .from(TEST_SUITE_METRIC_DEFINITIONS)
                .join(METRIC_DECLARATIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID.eq(METRIC_DECLARATIONS.ID))
                .where(TEST_SUITE_METRIC_DEFINITIONS
                        .ID
                        .eq(id.toString())
                        .and(TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID.eq(testSuiteId.toString())))
                .fetchOptional(this::mapWithDeclarationName);
    }

    @Override
    public Optional<AggregatedMetricDefinition> findAggregatedByIdAndTestSuiteId(UUID id, UUID testSuiteId) {
        try (ResultSet rs = dsl.select(
                        TEST_SUITE_METRIC_DEFINITIONS.ID,
                        TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.NAME,
                        TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED,
                        TEST_SUITE_METRIC_DEFINITIONS.CONDITION,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_VALID,
                        TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS,
                        TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS,
                        METRIC_DECLARATIONS.NAME.as("metric_declaration_name"),
                        METRIC_DECLARATIONS.PROVIDER_ID,
                        METRIC_DECLARATIONS.DESCRIPTION.as("declaration_description"),
                        METRIC_DECLARATIONS.CREATED_AT_MS.as("declaration_created_at_ms"),
                        METRIC_DECLARATION_VERSIONS.ID.as("version_id"),
                        METRIC_DECLARATION_VERSIONS.SCHEMA_VERSION,
                        METRIC_DECLARATION_VERSIONS.CONFIG_SCHEMA,
                        METRIC_DECLARATION_VERSIONS.INPUT_SCHEMA,
                        METRIC_DECLARATION_VERSIONS.OUTPUT_SCHEMA,
                        METRIC_DECLARATION_VERSIONS.DESCRIPTION.as("version_description"),
                        METRIC_DECLARATION_VERSIONS.CREATED_AT_MS.as("version_created_at_ms"))
                .from(TEST_SUITE_METRIC_DEFINITIONS)
                .join(METRIC_DECLARATIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID.eq(METRIC_DECLARATIONS.ID))
                .join(METRIC_DECLARATION_VERSIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID.eq(METRIC_DECLARATION_VERSIONS.ID))
                .where(TEST_SUITE_METRIC_DEFINITIONS
                        .ID
                        .eq(id.toString())
                        .and(TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID.eq(testSuiteId.toString())))
                .fetchResultSet()) {
            if (rs.next()) {
                return Optional.of(aggregatedRowMapper.mapRow(rs, 0));
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to fetch AggregatedMetricDefinition", ex);
        }
    }

    @Override
    public List<AggregatedMetricDefinition> findAllAggregatedByTestSuiteId(UUID testSuiteId) {
        return fetchAggregated(TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID.eq(testSuiteId.toString()));
    }

    @Override
    public List<AggregatedMetricDefinition> findAllEnabledAndValidAggregatedByTestSuiteId(UUID testSuiteId) {
        return fetchAggregated(TEST_SUITE_METRIC_DEFINITIONS
                .TEST_SUITE_ID
                .eq(testSuiteId.toString())
                .and(TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED.isTrue())
                .and(TEST_SUITE_METRIC_DEFINITIONS.IS_VALID.isTrue()));
    }

    @Override
    @Transactional("metaTransactionManager")
    public void updateValidation(UUID id, boolean valid, String warningsJson) {
        long now = transactionTimestampContext.getTimestamp();
        dsl.update(TEST_SUITE_METRIC_DEFINITIONS)
                .set(TEST_SUITE_METRIC_DEFINITIONS.IS_VALID, valid)
                .set(TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS, toJsonb(warningsJson))
                .set(TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS, now)
                .where(TEST_SUITE_METRIC_DEFINITIONS.ID.eq(id.toString()))
                .execute();
        log.debug("Updated validation for TestSuiteMetricDefinition with id: {}", id);
    }

    @Override
    public Page<TestSuiteMetricDefinition> findAll(
            UUID testSuiteId, PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }

        Condition filterCondition = whereBuilder.build(filters, FilterWhitelists.METRIC_DEFINITIONS);
        Condition baseCondition = TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID.eq(testSuiteId.toString());
        Condition combined = baseCondition.and(filterCondition);

        long totalCount = includeTotalCount ? countWithJoin(testSuiteId, filterCondition) : -1;
        List<SortField<?>> orderBy = orderByBuilder.build(pageRequest.getSort(), SortWhitelists.METRIC_DEFINITIONS);

        int limit = PageRequestSqlBuilder.limit(pageRequest);
        long offset = PageRequestSqlBuilder.offset(pageRequest);

        List<TestSuiteMetricDefinition> content = dsl.select(
                        TEST_SUITE_METRIC_DEFINITIONS.ID,
                        TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.NAME,
                        TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED,
                        TEST_SUITE_METRIC_DEFINITIONS.CONDITION,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_VALID,
                        TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS,
                        TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS,
                        METRIC_DECLARATIONS.NAME.as("metric_declaration_name"))
                .from(TEST_SUITE_METRIC_DEFINITIONS)
                .join(METRIC_DECLARATIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID.eq(METRIC_DECLARATIONS.ID))
                .where(combined)
                .orderBy(orderBy)
                .limit(limit)
                .offset(offset)
                .fetch(this::mapWithDeclarationName);

        return includeTotalCount ? Page.of(content, pageRequest, totalCount) : Page.withoutTotal(content, pageRequest);
    }

    @Override
    public TestSuiteMetricDefinition update(TestSuiteMetricDefinition entity) {
        long now = transactionTimestampContext.getTimestamp();
        entity.setUpdatedAt(now);

        dsl.update(TEST_SUITE_METRIC_DEFINITIONS)
                .set(
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                        entity.getMetricDeclarationId().toString())
                .set(
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                        entity.getMetricDeclarationVersionId().toString())
                .set(TEST_SUITE_METRIC_DEFINITIONS.NAME, entity.getName())
                .set(TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS, toJsonb(entity.getConfigBindings()))
                .set(TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS, toJsonb(entity.getInputBindings()))
                .set(TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED, entity.isEnabled())
                .set(TEST_SUITE_METRIC_DEFINITIONS.CONDITION, entity.getCondition())
                .set(TEST_SUITE_METRIC_DEFINITIONS.IS_VALID, entity.isValid())
                .set(TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS, toJsonb(entity.getValidationWarnings()))
                .set(TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS, entity.getUpdatedAt())
                .where(TEST_SUITE_METRIC_DEFINITIONS.ID.eq(entity.getId().toString()))
                .execute();
        log.debug("Updated TestSuiteMetricDefinition with id: {}", entity.getId());
        return entity;
    }

    @Override
    public boolean deleteById(UUID id) {
        int deleted = dsl.deleteFrom(TEST_SUITE_METRIC_DEFINITIONS)
                .where(TEST_SUITE_METRIC_DEFINITIONS.ID.eq(id.toString()))
                .execute();
        log.debug("Deleted TestSuiteMetricDefinition with id: {}, rows affected: {}", id, deleted);
        return deleted > 0;
    }

    @Override
    public void deleteByTestSuiteId(UUID testSuiteId) {
        int deleted = dsl.deleteFrom(TEST_SUITE_METRIC_DEFINITIONS)
                .where(TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID.eq(testSuiteId.toString()))
                .execute();
        log.debug("Deleted TSMDs for test suite: {}, rows affected: {}", testSuiteId, deleted);
    }

    @Override
    public long count(UUID testSuiteId) {
        Long count = dsl.selectCount()
                .from(TEST_SUITE_METRIC_DEFINITIONS)
                .where(TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID.eq(testSuiteId.toString()))
                .fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public void batchInsert(List<TestSuiteMetricDefinition> tsmds, long timestamp) {
        if (tsmds == null || tsmds.isEmpty()) {
            return;
        }
        List<Query> queries = tsmds.stream()
                .map(tsmd -> (Query) dsl.insertInto(TEST_SUITE_METRIC_DEFINITIONS)
                        .set(TEST_SUITE_METRIC_DEFINITIONS.ID, tsmd.getId().toString())
                        .set(
                                TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID,
                                tsmd.getTestSuiteId().toString())
                        .set(
                                TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                                tsmd.getMetricDeclarationId().toString())
                        .set(
                                TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                                tsmd.getMetricDeclarationVersionId().toString())
                        .set(TEST_SUITE_METRIC_DEFINITIONS.NAME, tsmd.getName())
                        .set(TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS, toJsonb(tsmd.getConfigBindings()))
                        .set(TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS, toJsonb(tsmd.getInputBindings()))
                        .set(TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED, tsmd.isEnabled())
                        .set(TEST_SUITE_METRIC_DEFINITIONS.CONDITION, tsmd.getCondition())
                        .set(TEST_SUITE_METRIC_DEFINITIONS.IS_VALID, tsmd.isValid())
                        .set(TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS, toJsonb(tsmd.getValidationWarnings()))
                        .set(TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS, timestamp)
                        .set(TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS, timestamp))
                .toList();
        dsl.batch(queries).execute();
    }

    @Override
    public List<TestSuiteMetricDefinition> findBatchByTestSuiteId(UUID testSuiteId, int offset, int limit) {
        return dsl.select(
                        TEST_SUITE_METRIC_DEFINITIONS.ID,
                        TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.NAME,
                        TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED,
                        TEST_SUITE_METRIC_DEFINITIONS.CONDITION,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_VALID,
                        TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS,
                        TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS,
                        METRIC_DECLARATIONS.NAME.as("metric_declaration_name"))
                .from(TEST_SUITE_METRIC_DEFINITIONS)
                .join(METRIC_DECLARATIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID.eq(METRIC_DECLARATIONS.ID))
                .where(TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID.eq(testSuiteId.toString()))
                .orderBy(TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS.asc())
                .limit(limit)
                .offset(offset)
                .fetch(this::mapWithDeclarationName);
    }

    private long countWithJoin(UUID testSuiteId, Condition filterCondition) {
        Condition combined = TEST_SUITE_METRIC_DEFINITIONS
                .TEST_SUITE_ID
                .eq(testSuiteId.toString())
                .and(filterCondition);
        Long count = dsl.selectCount()
                .from(TEST_SUITE_METRIC_DEFINITIONS)
                .join(METRIC_DECLARATIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID.eq(METRIC_DECLARATIONS.ID))
                .where(combined)
                .fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    private List<AggregatedMetricDefinition> fetchAggregated(Condition condition) {
        try (ResultSet rs = dsl.select(
                        TEST_SUITE_METRIC_DEFINITIONS.ID,
                        TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID,
                        TEST_SUITE_METRIC_DEFINITIONS.NAME,
                        TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED,
                        TEST_SUITE_METRIC_DEFINITIONS.CONDITION,
                        TEST_SUITE_METRIC_DEFINITIONS.IS_VALID,
                        TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS,
                        TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS,
                        TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS,
                        METRIC_DECLARATIONS.NAME.as("metric_declaration_name"),
                        METRIC_DECLARATIONS.PROVIDER_ID,
                        METRIC_DECLARATIONS.DESCRIPTION.as("declaration_description"),
                        METRIC_DECLARATIONS.CREATED_AT_MS.as("declaration_created_at_ms"),
                        METRIC_DECLARATION_VERSIONS.ID.as("version_id"),
                        METRIC_DECLARATION_VERSIONS.SCHEMA_VERSION,
                        METRIC_DECLARATION_VERSIONS.CONFIG_SCHEMA,
                        METRIC_DECLARATION_VERSIONS.INPUT_SCHEMA,
                        METRIC_DECLARATION_VERSIONS.OUTPUT_SCHEMA,
                        METRIC_DECLARATION_VERSIONS.DESCRIPTION.as("version_description"),
                        METRIC_DECLARATION_VERSIONS.CREATED_AT_MS.as("version_created_at_ms"))
                .from(TEST_SUITE_METRIC_DEFINITIONS)
                .join(METRIC_DECLARATIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID.eq(METRIC_DECLARATIONS.ID))
                .join(METRIC_DECLARATION_VERSIONS)
                .on(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID.eq(METRIC_DECLARATION_VERSIONS.ID))
                .where(condition)
                .fetchResultSet()) {
            List<AggregatedMetricDefinition> results = new ArrayList<>();
            int rowNum = 0;
            while (rs.next()) {
                results.add(aggregatedRowMapper.mapRow(rs, rowNum++));
            }
            return results;
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to fetch AggregatedMetricDefinition list", ex);
        }
    }

    private TestSuiteMetricDefinition mapWithDeclarationName(Record r) {
        return TestSuiteMetricDefinition.builder()
                .id(UUID.fromString(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.ID)))
                .testSuiteId(UUID.fromString(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.TEST_SUITE_ID)))
                .metricDeclarationId(UUID.fromString(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_ID)))
                .metricDeclarationVersionId(
                        UUID.fromString(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.METRIC_DECLARATION_VERSION_ID)))
                .name(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.NAME))
                .configBindings(toJsonString(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.CONFIG_BINDINGS)))
                .inputBindings(toJsonString(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.INPUT_BINDINGS)))
                .enabled(Boolean.TRUE.equals(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.IS_ENABLED)))
                .condition(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.CONDITION))
                .valid(Boolean.TRUE.equals(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.IS_VALID)))
                .validationWarnings(toJsonString(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.VALIDATION_WARNINGS)))
                .metricDeclarationName(r.getValue("metric_declaration_name", String.class))
                .createdAt(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS))
                .updatedAt(r.getValue(TEST_SUITE_METRIC_DEFINITIONS.UPDATED_AT_MS))
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
