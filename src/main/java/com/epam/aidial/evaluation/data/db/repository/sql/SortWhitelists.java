package com.epam.aidial.evaluation.data.db.repository.sql;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.Datasets;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.MetricDeclarations;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.TestCases;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.TestSuiteMetricDefinitions;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.TestSuiteRuns;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.TestSuites;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.model.pagination.SortKey;
import java.util.List;
import java.util.Map;

public final class SortWhitelists {

    public static final SortSpec DATASETS = SortSpec.of(
            Map.of(
                    "id", Datasets.DATASETS.ID,
                    "name", Datasets.DATASETS.NAME,
                    "createdBy", Datasets.DATASETS.CREATED_BY,
                    "createdAt", Datasets.DATASETS.CREATED_AT_MS,
                    "updatedAt", Datasets.DATASETS.UPDATED_AT_MS,
                    "version", Datasets.DATASETS.VERSION),
            List.of(SortKey.builder()
                    .field("createdAt")
                    .direction(PageRequest.SortDirection.DESC)
                    .build()));

    public static final SortSpec TEST_SUITES = SortSpec.of(
            Map.of(
                    "id", TestSuites.TEST_SUITES.ID,
                    "name", TestSuites.TEST_SUITES.NAME,
                    "createdBy", TestSuites.TEST_SUITES.CREATED_BY,
                    "createdAt", TestSuites.TEST_SUITES.CREATED_AT_MS,
                    "updatedAt", TestSuites.TEST_SUITES.UPDATED_AT_MS),
            List.of(SortKey.builder()
                    .field("createdAt")
                    .direction(PageRequest.SortDirection.DESC)
                    .build()));

    public static final SortSpec TEST_CASES = SortSpec.of(
            Map.of(
                    "id", TestCases.TEST_CASES.ID,
                    "testCaseName", TestCases.TEST_CASES.TEST_CASE_NAME,
                    "createdAt", TestCases.TEST_CASES.CREATED_AT_MS,
                    "updatedAt", TestCases.TEST_CASES.UPDATED_AT_MS,
                    "valid", TestCases.TEST_CASES.IS_VALID),
            List.of(SortKey.builder()
                    .field("createdAt")
                    .direction(PageRequest.SortDirection.DESC)
                    .build()));

    public static final SortSpec TEST_SUITE_RUNS = SortSpec.of(
            Map.of(
                    "id", TestSuiteRuns.TEST_SUITE_RUNS.ID,
                    "testRunName", TestSuiteRuns.TEST_SUITE_RUNS.TEST_RUN_NAME,
                    "status", TestSuiteRuns.TEST_SUITE_RUNS.STATUS,
                    "createdAt", TestSuiteRuns.TEST_SUITE_RUNS.CREATED_AT_MS,
                    "startedAt", TestSuiteRuns.TEST_SUITE_RUNS.STARTED_AT_MS,
                    "completedAt", TestSuiteRuns.TEST_SUITE_RUNS.COMPLETED_AT_MS),
            List.of(SortKey.builder()
                    .field("createdAt")
                    .direction(PageRequest.SortDirection.DESC)
                    .build()));

    public static final SortSpec METRIC_DECLARATIONS = SortSpec.of(
            Map.of(
                    "id", MetricDeclarations.METRIC_DECLARATIONS.ID,
                    "name", MetricDeclarations.METRIC_DECLARATIONS.NAME,
                    "providerId", MetricDeclarations.METRIC_DECLARATIONS.PROVIDER_ID,
                    "createdAt", MetricDeclarations.METRIC_DECLARATIONS.CREATED_AT_MS),
            List.of(SortKey.builder()
                    .field("createdAt")
                    .direction(PageRequest.SortDirection.DESC)
                    .build()));

    public static final SortSpec METRIC_DEFINITIONS = SortSpec.of(
            Map.of(
                    "name", TestSuiteMetricDefinitions.TEST_SUITE_METRIC_DEFINITIONS.NAME,
                    "createdAt", TestSuiteMetricDefinitions.TEST_SUITE_METRIC_DEFINITIONS.CREATED_AT_MS),
            List.of(SortKey.builder()
                    .field("createdAt")
                    .direction(PageRequest.SortDirection.DESC)
                    .build()));

    private SortWhitelists() {}
}
