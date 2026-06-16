package com.epam.aidial.evaluation.data.db.repository.sql;

import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.TestCaseEvalSummaries;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.TestCaseRunResults;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.Datasets;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.MetricDeclarations;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.TestCases;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.TestSuiteMetricDefinitions;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.TestSuiteRuns;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.TestSuites;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import java.util.EnumSet;
import java.util.Map;

public final class FilterWhitelists {

    public static final FilterSpec DATASETS = FilterSpec.of(Map.ofEntries(
            Map.entry(
                    "id",
                    FilterFieldDefinition.of(
                            Datasets.DATASETS.ID,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "name",
                    FilterFieldDefinition.of(
                            Datasets.DATASETS.NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO, FilterOperator.IN))),
            Map.entry(
                    "description",
                    FilterFieldDefinition.of(
                            Datasets.DATASETS.DESCRIPTION, FilterFieldType.STRING, EnumSet.of(FilterOperator.CO))),
            Map.entry(
                    "createdBy",
                    FilterFieldDefinition.of(
                            Datasets.DATASETS.CREATED_BY,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN))),
            Map.entry(
                    "createdAt",
                    FilterFieldDefinition.of(
                            Datasets.DATASETS.CREATED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE))),
            Map.entry(
                    "updatedAt",
                    FilterFieldDefinition.of(
                            Datasets.DATASETS.UPDATED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE)))));

    public static final FilterSpec TEST_SUITES = FilterSpec.of(Map.ofEntries(
            Map.entry(
                    "id",
                    FilterFieldDefinition.of(
                            TestSuites.TEST_SUITES.ID,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "name",
                    FilterFieldDefinition.of(
                            TestSuites.TEST_SUITES.NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO, FilterOperator.IN))),
            Map.entry(
                    "description",
                    FilterFieldDefinition.of(
                            TestSuites.TEST_SUITES.DESCRIPTION, FilterFieldType.STRING, EnumSet.of(FilterOperator.CO))),
            Map.entry(
                    "suiteType",
                    FilterFieldDefinition.of(
                            TestSuites.TEST_SUITES.SUITE_TYPE,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "createdBy",
                    FilterFieldDefinition.of(
                            TestSuites.TEST_SUITES.CREATED_BY,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN))),
            Map.entry(
                    "createdAt",
                    FilterFieldDefinition.of(
                            TestSuites.TEST_SUITES.CREATED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE))),
            Map.entry(
                    "updatedAt",
                    FilterFieldDefinition.of(
                            TestSuites.TEST_SUITES.UPDATED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE)))));

    public static final FilterSpec TEST_CASES = FilterSpec.of(Map.of(
            "testCaseName",
                    FilterFieldDefinition.of(
                            TestCases.TEST_CASES.TEST_CASE_NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO, FilterOperator.IN)),
            "valid",
                    FilterFieldDefinition.of(
                            TestCases.TEST_CASES.IS_VALID,
                            FilterFieldType.BOOLEAN,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE)),
            "createdAt",
                    FilterFieldDefinition.of(
                            TestCases.TEST_CASES.CREATED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE))));

    public static final FilterSpec TEST_SUITE_RUNS = FilterSpec.of(Map.of(
            "id",
                    FilterFieldDefinition.of(
                            TestSuiteRuns.TEST_SUITE_RUNS.ID,
                            FilterFieldType.UUID,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN)),
            "testSuiteId",
                    FilterFieldDefinition.of(
                            TestSuiteRuns.TEST_SUITE_RUNS.TEST_SUITE_ID,
                            FilterFieldType.UUID,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN)),
            "status",
                    FilterFieldDefinition.of(
                            TestSuiteRuns.TEST_SUITE_RUNS.STATUS,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN)),
            "testRunName",
                    FilterFieldDefinition.of(
                            TestSuiteRuns.TEST_SUITE_RUNS.TEST_RUN_NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO, FilterOperator.IN)),
            "createdAt",
                    FilterFieldDefinition.of(
                            TestSuiteRuns.TEST_SUITE_RUNS.CREATED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE)),
            "startedAt",
                    FilterFieldDefinition.of(
                            TestSuiteRuns.TEST_SUITE_RUNS.STARTED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE)),
            "completedAt",
                    FilterFieldDefinition.of(
                            TestSuiteRuns.TEST_SUITE_RUNS.COMPLETED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE))));

    public static final FilterSpec METRIC_DECLARATIONS = FilterSpec.of(Map.of(
            "name",
                    FilterFieldDefinition.of(
                            MetricDeclarations.METRIC_DECLARATIONS.NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO, FilterOperator.IN)),
            "providerId",
                    FilterFieldDefinition.of(
                            MetricDeclarations.METRIC_DECLARATIONS.PROVIDER_ID,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN)),
            "createdAt",
                    FilterFieldDefinition.of(
                            MetricDeclarations.METRIC_DECLARATIONS.CREATED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE))));

    public static final FilterSpec METRIC_DEFINITIONS = FilterSpec.of(Map.of(
            "name",
                    FilterFieldDefinition.of(
                            TestSuiteMetricDefinitions.TEST_SUITE_METRIC_DEFINITIONS.NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO)),
            "metricDeclarationName",
                    FilterFieldDefinition.of(
                            MetricDeclarations.METRIC_DECLARATIONS.NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO))));

    public static final FilterSpec EVAL_SUMMARIES = FilterSpec.of(Map.ofEntries(
            Map.entry(
                    "suiteId",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID,
                            FilterFieldType.UUID,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "runId",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID,
                            FilterFieldType.UUID,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "testCaseId",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID,
                            FilterFieldType.UUID,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "testCaseName",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO, FilterOperator.IN))),
            Map.entry(
                    "executionStatus",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN))),
            Map.entry(
                    "runIndex",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.RUN_INDEX,
                            FilterFieldType.LONG,
                            EnumSet.of(
                                    FilterOperator.EQ,
                                    FilterOperator.GT,
                                    FilterOperator.GE,
                                    FilterOperator.LT,
                                    FilterOperator.LE))),
            Map.entry(
                    "execDurationMs",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE))),
            Map.entry(
                    "responseStatusCode",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE,
                            FilterFieldType.LONG,
                            EnumSet.of(
                                    FilterOperator.EQ,
                                    FilterOperator.GT,
                                    FilterOperator.GE,
                                    FilterOperator.LT,
                                    FilterOperator.LE))),
            Map.entry(
                    "testCaseData",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA,
                            FilterFieldType.JSONB_STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO))),
            Map.entry(
                    "metricValues",
                    FilterFieldDefinition.of(
                            TestCaseEvalSummaries.TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES,
                            FilterFieldType.JSONB_NUMERIC,
                            EnumSet.of(
                                    FilterOperator.EQ,
                                    FilterOperator.NE,
                                    FilterOperator.GT,
                                    FilterOperator.GE,
                                    FilterOperator.LT,
                                    FilterOperator.LE)))));

    public static final FilterSpec ANALYTICS_RESULTS = FilterSpec.of(Map.ofEntries(
            Map.entry(
                    "runId",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.TEST_SUITE_RUN_ID,
                            FilterFieldType.UUID,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "suiteId",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.TEST_SUITE_ID,
                            FilterFieldType.UUID,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "testCaseId",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.TEST_CASE_ID,
                            FilterFieldType.UUID,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.IN))),
            Map.entry(
                    "testCaseName",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.TEST_CASE_NAME,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO, FilterOperator.IN))),
            Map.entry(
                    "executionStatus",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.EXECUTION_STATUS,
                            FilterFieldType.STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN))),
            Map.entry(
                    "runIndex",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.RUN_INDEX,
                            FilterFieldType.LONG,
                            EnumSet.of(
                                    FilterOperator.EQ,
                                    FilterOperator.GT,
                                    FilterOperator.GE,
                                    FilterOperator.LT,
                                    FilterOperator.LE))),
            Map.entry(
                    "createdAt",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.CREATED_AT_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE))),
            Map.entry(
                    "execDurationMs",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.EXEC_DURATION_MS,
                            FilterFieldType.LONG,
                            EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE))),
            Map.entry(
                    "responseStatusCode",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.RESPONSE_STATUS_CODE,
                            FilterFieldType.LONG,
                            EnumSet.of(
                                    FilterOperator.EQ,
                                    FilterOperator.GT,
                                    FilterOperator.GE,
                                    FilterOperator.LT,
                                    FilterOperator.LE))),
            Map.entry(
                    "retryCount",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.RETRY_COUNT,
                            FilterFieldType.LONG,
                            EnumSet.of(
                                    FilterOperator.EQ,
                                    FilterOperator.GT,
                                    FilterOperator.GE,
                                    FilterOperator.LT,
                                    FilterOperator.LE))),
            Map.entry(
                    "testCaseData",
                    FilterFieldDefinition.of(
                            TestCaseRunResults.TEST_CASE_RUN_RESULTS.TEST_CASE_DATA,
                            FilterFieldType.JSONB_STRING,
                            EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO)))));

    private FilterWhitelists() {}
}
