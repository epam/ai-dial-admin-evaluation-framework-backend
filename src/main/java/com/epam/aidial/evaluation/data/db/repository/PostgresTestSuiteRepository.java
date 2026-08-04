package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;

import com.epam.aidial.evaluation.data.db.exception.OptimisticLockException;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.TestSuitesRecord;
import com.epam.aidial.evaluation.data.db.mapper.TestSuiteRecordMapper;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSummary;
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
import org.jooq.JSONB;
import org.jooq.SortField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestSuiteRepository implements TestSuiteRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final TestSuiteRecordMapper recordMapper;
    private final TransactionTimestampContext transactionTimestampContext;
    private final WhereBuilder whereBuilder;
    private final OrderByBuilder orderByBuilder;

    @Override
    public Page<TestSuite> findAll(PageRequest pageRequest) {
        return findAll(pageRequest, List.of(), true);
    }

    @Override
    public Page<TestSuite> findAll(PageRequest pageRequest, boolean includeTotalCount) {
        return findAll(pageRequest, List.of(), includeTotalCount);
    }

    @Override
    public Page<TestSuite> findAll(PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }

        Condition condition = whereBuilder.build(filters, FilterWhitelists.TEST_SUITES);
        long totalCount = includeTotalCount ? count(condition) : -1;
        List<SortField<?>> orderBy = orderByBuilder.build(pageRequest.getSort(), SortWhitelists.TEST_SUITES);

        int limit = PageRequestSqlBuilder.limit(pageRequest);
        long offset = PageRequestSqlBuilder.offset(pageRequest);

        List<TestSuite> content = dsl.selectFrom(TEST_SUITES)
                .where(condition)
                .orderBy(orderBy)
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);

        return includeTotalCount ? Page.of(content, pageRequest, totalCount) : Page.withoutTotal(content, pageRequest);
    }

    @Override
    public Optional<TestSuite> findById(UUID id) {
        return dsl.selectFrom(TEST_SUITES)
                .where(TEST_SUITES.ID.eq(id.toString()))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public TestSuite save(TestSuite testSuite) {
        boolean isNew = testSuite.getId() == null;
        return isNew ? create(testSuite) : update(testSuite);
    }

    private TestSuite create(TestSuite testSuite) {
        long now = transactionTimestampContext.getTimestamp();
        testSuite.setId(UUID.randomUUID());
        if (testSuite.getVersion() == null) {
            testSuite.setVersion(0L);
        }
        testSuite.setCreatedAt(now);
        testSuite.setUpdatedAt(now);

        dsl.insertInto(TEST_SUITES)
                .set(TEST_SUITES.ID, testSuite.getId().toString())
                .set(TEST_SUITES.NAME, testSuite.getName())
                .set(TEST_SUITES.DESCRIPTION, testSuite.getDescription())
                .set(
                        TEST_SUITES.SUITE_TYPE,
                        testSuite.getSuiteType() != null
                                ? testSuite.getSuiteType().getValue()
                                : SuiteType.DEPLOYMENT.getValue())
                .set(
                        TEST_SUITES.DATASET_ID,
                        testSuite.getDatasetId() != null
                                ? testSuite.getDatasetId().toString()
                                : null)
                .set(TEST_SUITES.DISABLED_TEST_CASE_IDS, toJsonb(testSuite.getDisabledTestCaseIds()))
                .set(TEST_SUITES.DEPLOYMENT_REF, toJsonb(testSuite.getDeploymentRef()))
                .set(TEST_SUITES.ENDPOINT_REF, toJsonb(testSuite.getEndpointRef()))
                .set(TEST_SUITES.RESPONSE_COLUMNS, toJsonb(testSuite.getResponseColumns()))
                .set(TEST_SUITES.REQUEST_TEMPLATE, toJsonb(testSuite.getRequestTemplate()))
                .set(TEST_SUITES.INPUT_BINDINGS, toJsonb(testSuite.getInputBindings()))
                .set(TEST_SUITES.MCP_DEPLOYMENT_REF, toJsonb(testSuite.getMcpDeploymentRef()))
                .set(TEST_SUITES.TOOL_REF, toJsonb(testSuite.getToolRef()))
                .set(TEST_SUITES.ARGUMENT_TEMPLATE, toJsonb(testSuite.getArgumentTemplate()))
                .set(TEST_SUITES.ADDITIONAL_REQUESTS, toJsonb(testSuite.getAdditionalRequests()))
                .set(TEST_SUITES.REQUEST_NAME, testSuite.getRequestName())
                .set(TEST_SUITES.OVERALL_SCORE, toJsonb(testSuite.getOverallScore()))
                .set(TEST_SUITES.OVERALL_SCORE_THRESHOLD, testSuite.getOverallScoreThreshold())
                .set(TEST_SUITES.TEST_CASE_FILTER, toJsonb(testSuite.getTestCaseFilter()))
                .set(TEST_SUITES.IS_VALID, testSuite.isValid())
                .set(TEST_SUITES.VALIDATION_WARNINGS, toJsonb(testSuite.getValidationWarnings()))
                .set(TEST_SUITES.VERSION, testSuite.getVersion())
                .set(TEST_SUITES.CREATED_BY, testSuite.getCreatedBy())
                .set(TEST_SUITES.CREATED_AT_MS, testSuite.getCreatedAt())
                .set(TEST_SUITES.UPDATED_AT_MS, testSuite.getUpdatedAt())
                .execute();
        log.debug("Created TestSuite with id: {}", testSuite.getId());
        return testSuite;
    }

    private TestSuite update(TestSuite testSuite) {
        long now = transactionTimestampContext.getTimestamp();
        Long version = testSuite.getVersion();
        if (version == null) {
            throw new IllegalArgumentException("Version is required for update (optimistic locking)");
        }

        TestSuitesRecord updated = dsl.update(TEST_SUITES)
                .set(TEST_SUITES.NAME, testSuite.getName())
                .set(TEST_SUITES.DESCRIPTION, testSuite.getDescription())
                .set(
                        TEST_SUITES.DATASET_ID,
                        testSuite.getDatasetId() != null
                                ? testSuite.getDatasetId().toString()
                                : null)
                .set(TEST_SUITES.DISABLED_TEST_CASE_IDS, toJsonb(testSuite.getDisabledTestCaseIds()))
                .set(TEST_SUITES.DEPLOYMENT_REF, toJsonb(testSuite.getDeploymentRef()))
                .set(TEST_SUITES.ENDPOINT_REF, toJsonb(testSuite.getEndpointRef()))
                .set(TEST_SUITES.RESPONSE_COLUMNS, toJsonb(testSuite.getResponseColumns()))
                .set(TEST_SUITES.REQUEST_TEMPLATE, toJsonb(testSuite.getRequestTemplate()))
                .set(TEST_SUITES.INPUT_BINDINGS, toJsonb(testSuite.getInputBindings()))
                .set(TEST_SUITES.MCP_DEPLOYMENT_REF, toJsonb(testSuite.getMcpDeploymentRef()))
                .set(TEST_SUITES.TOOL_REF, toJsonb(testSuite.getToolRef()))
                .set(TEST_SUITES.ARGUMENT_TEMPLATE, toJsonb(testSuite.getArgumentTemplate()))
                .set(TEST_SUITES.ADDITIONAL_REQUESTS, toJsonb(testSuite.getAdditionalRequests()))
                .set(TEST_SUITES.REQUEST_NAME, testSuite.getRequestName())
                .set(TEST_SUITES.OVERALL_SCORE, toJsonb(testSuite.getOverallScore()))
                .set(TEST_SUITES.OVERALL_SCORE_THRESHOLD, testSuite.getOverallScoreThreshold())
                .set(TEST_SUITES.TEST_CASE_FILTER, toJsonb(testSuite.getTestCaseFilter()))
                .set(TEST_SUITES.IS_VALID, testSuite.isValid())
                .set(TEST_SUITES.VALIDATION_WARNINGS, toJsonb(testSuite.getValidationWarnings()))
                .set(TEST_SUITES.VERSION, TEST_SUITES.VERSION.add(1))
                .set(TEST_SUITES.UPDATED_AT_MS, now)
                .where(TEST_SUITES.ID.eq(testSuite.getId().toString()).and(TEST_SUITES.VERSION.eq(version)))
                .returning()
                .fetchOne();

        if (updated == null) {
            throw new OptimisticLockException(
                    "TestSuite version conflict: expected version " + version + " for id " + testSuite.getId());
        }
        log.debug("Updated TestSuite with id: {}", testSuite.getId());
        return recordMapper.map(updated);
    }

    @Override
    public long count() {
        Long count = dsl.selectCount().from(TEST_SUITES).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    private long count(Condition condition) {
        Long count = dsl.selectCount().from(TEST_SUITES).where(condition).fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public boolean deleteById(UUID id) {
        // test_cases (under suite's dataset) and revalidation_tasks remain owned by the dataset; FK constraints
        // mean a suite delete only removes the suite row itself plus its dependent test_suite_runs / TSMDs.
        int deleted = dsl.deleteFrom(TEST_SUITES)
                .where(TEST_SUITES.ID.eq(id.toString()))
                .execute();
        log.debug("Deleted TestSuite with id: {}, rows affected: {}", id, deleted);
        return deleted > 0;
    }

    @Override
    public boolean existsById(UUID id) {
        return dsl.fetchExists(TEST_SUITES, TEST_SUITES.ID.eq(id.toString()));
    }

    @Override
    public void updateIsValid(UUID id, boolean isValid) {
        dsl.update(TEST_SUITES)
                .set(TEST_SUITES.IS_VALID, isValid)
                .where(TEST_SUITES.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public void updateValidation(UUID id, boolean isValid, String validationWarningsJson, long updatedAt) {
        dsl.update(TEST_SUITES)
                .set(TEST_SUITES.IS_VALID, isValid)
                .set(TEST_SUITES.VALIDATION_WARNINGS, toJsonb(validationWarningsJson))
                .set(TEST_SUITES.UPDATED_AT_MS, updatedAt)
                .where(TEST_SUITES.ID.eq(id.toString()))
                .execute();
    }

    @Override
    public List<TestSuite> findSuitesReferencingDataset(UUID datasetId) {
        return dsl.selectFrom(TEST_SUITES)
                .where(TEST_SUITES.DATASET_ID.eq(datasetId.toString()))
                .fetch(recordMapper::map);
    }

    @Override
    public List<TestSuiteSummary> findSuiteSummariesReferencingDataset(UUID datasetId) {
        return dsl.select(TEST_SUITES.ID, TEST_SUITES.NAME, TEST_SUITES.DESCRIPTION)
                .from(TEST_SUITES)
                .where(TEST_SUITES.DATASET_ID.eq(datasetId.toString()))
                .fetch(r -> new TestSuiteSummary(
                        UUID.fromString(r.get(TEST_SUITES.ID)),
                        r.get(TEST_SUITES.NAME),
                        r.get(TEST_SUITES.DESCRIPTION)));
    }

    @Override
    public int unbindAllByDatasetId(UUID datasetId) {
        int updated = dsl.update(TEST_SUITES)
                .setNull(TEST_SUITES.DATASET_ID)
                .where(TEST_SUITES.DATASET_ID.eq(datasetId.toString()))
                .execute();
        log.debug("Unbound {} suite(s) from dataset {}", updated, datasetId);
        return updated;
    }

    @Override
    public long countByDatasetId(UUID datasetId) {
        Long count = dsl.selectCount()
                .from(TEST_SUITES)
                .where(TEST_SUITES.DATASET_ID.eq(datasetId.toString()))
                .fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public TestSuite createWithId(TestSuite testSuite, long timestamp) {
        testSuite.setCreatedAt(timestamp);
        testSuite.setUpdatedAt(timestamp);
        if (testSuite.getVersion() == null) {
            testSuite.setVersion(0L);
        }

        dsl.insertInto(TEST_SUITES)
                .set(TEST_SUITES.ID, testSuite.getId().toString())
                .set(TEST_SUITES.NAME, testSuite.getName())
                .set(TEST_SUITES.DESCRIPTION, testSuite.getDescription())
                .set(
                        TEST_SUITES.SUITE_TYPE,
                        testSuite.getSuiteType() != null
                                ? testSuite.getSuiteType().getValue()
                                : SuiteType.DEPLOYMENT.getValue())
                .set(
                        TEST_SUITES.DATASET_ID,
                        testSuite.getDatasetId() != null
                                ? testSuite.getDatasetId().toString()
                                : null)
                .set(TEST_SUITES.DISABLED_TEST_CASE_IDS, toJsonb(testSuite.getDisabledTestCaseIds()))
                .set(TEST_SUITES.DEPLOYMENT_REF, toJsonb(testSuite.getDeploymentRef()))
                .set(TEST_SUITES.ENDPOINT_REF, toJsonb(testSuite.getEndpointRef()))
                .set(TEST_SUITES.RESPONSE_COLUMNS, toJsonb(testSuite.getResponseColumns()))
                .set(TEST_SUITES.REQUEST_TEMPLATE, toJsonb(testSuite.getRequestTemplate()))
                .set(TEST_SUITES.INPUT_BINDINGS, toJsonb(testSuite.getInputBindings()))
                .set(TEST_SUITES.MCP_DEPLOYMENT_REF, toJsonb(testSuite.getMcpDeploymentRef()))
                .set(TEST_SUITES.TOOL_REF, toJsonb(testSuite.getToolRef()))
                .set(TEST_SUITES.ARGUMENT_TEMPLATE, toJsonb(testSuite.getArgumentTemplate()))
                .set(TEST_SUITES.ADDITIONAL_REQUESTS, toJsonb(testSuite.getAdditionalRequests()))
                .set(TEST_SUITES.REQUEST_NAME, testSuite.getRequestName())
                .set(TEST_SUITES.OVERALL_SCORE, toJsonb(testSuite.getOverallScore()))
                .set(TEST_SUITES.OVERALL_SCORE_THRESHOLD, testSuite.getOverallScoreThreshold())
                .set(TEST_SUITES.TEST_CASE_FILTER, toJsonb(testSuite.getTestCaseFilter()))
                .set(TEST_SUITES.IS_VALID, testSuite.isValid())
                .set(TEST_SUITES.VALIDATION_WARNINGS, toJsonb(testSuite.getValidationWarnings()))
                .set(TEST_SUITES.VERSION, testSuite.getVersion())
                .set(TEST_SUITES.CREATED_BY, testSuite.getCreatedBy())
                .set(TEST_SUITES.CREATED_AT_MS, timestamp)
                .set(TEST_SUITES.UPDATED_AT_MS, timestamp)
                .execute();
        log.debug("Created TestSuite with supplied id: {}", testSuite.getId());
        return testSuite;
    }

    @Override
    public void updateDatasetId(UUID suiteId, UUID newDatasetId, String disabledTestCaseIds, long updatedAt) {
        dsl.update(TEST_SUITES)
                .set(TEST_SUITES.DATASET_ID, newDatasetId.toString())
                .set(TEST_SUITES.DISABLED_TEST_CASE_IDS, toJsonb(disabledTestCaseIds))
                .set(TEST_SUITES.VERSION, TEST_SUITES.VERSION.add(1))
                .set(TEST_SUITES.UPDATED_AT_MS, updatedAt)
                .where(TEST_SUITES.ID.eq(suiteId.toString()))
                .execute();
        log.debug("Rebound suite {} to dataset {}", suiteId, newDatasetId);
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
