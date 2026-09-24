package com.epam.aidial.evaluation.query.service;

import java.util.List;
import java.util.Set;

/**
 * Shared constants for the {@code test_suite_runs} structured-query entity, consumed by both
 * {@code PostgresTestSuiteRunEntityResolver} and {@link TestSuiteRunsSchemaProvider} so the
 * executable field set and the published schema cannot drift apart.
 */
public final class TestSuiteRunQueryFields {

    /** Wire name of the entity. */
    public static final String ENTITY = "test_suite_runs";

    /** Separator joining a snapshot reference's field prefix and sub-key, e.g. {@code deployment_ref::id}. */
    public static final String FIELD_SEPARATOR = "::";

    public static final String SUITE_SNAPSHOT_COLUMN = "suite_snapshot";

    /**
     * The {@code run_config} column stays in {@link #EXCLUDED_COLUMNS} (never projected as a column
     * or accepted in {@code filter}/{@code select}/{@code sort}/{@code group_by}), but it is also the
     * published {@code source} of {@link #NUMBER_OF_RUNS_FIELD}, a single scalar read from it.
     */
    public static final String RUN_CONFIG_COLUMN = "run_config";

    public static final String ERROR_DETAILS_COLUMN = "error_details";

    /** Columns of the generated {@code TEST_SUITE_RUNS} table not exposed by this entity. */
    public static final Set<String> EXCLUDED_COLUMNS =
            Set.of(SUITE_SNAPSHOT_COLUMN, RUN_CONFIG_COLUMN, ERROR_DETAILS_COLUMN);

    /** Flat field extracting {@code suite_snapshot ->> 'suiteType'}. */
    public static final String SUITE_TYPE_FIELD = "suite_type";

    /** Key of {@code suiteType} inside {@code suite_snapshot}. */
    public static final String SUITE_TYPE_SNAPSHOT_KEY = "suiteType";

    /**
     * Flat field extracting {@code (run_config ->> 'numberOfRuns')::integer}. {@code run_config}
     * itself remains excluded (see {@link #RUN_CONFIG_COLUMN}); only this one scalar is published.
     */
    public static final String NUMBER_OF_RUNS_FIELD = "number_of_runs";

    /** Key of {@code numberOfRuns} inside {@code run_config}. */
    public static final String NUMBER_OF_RUNS_CONFIG_KEY = "numberOfRuns";

    /** Flat field carrying the run's latest-computation metric names. */
    public static final String METRIC_NAMES_FIELD = "metric_names";

    /** {@code source} label published for {@link #METRIC_NAMES_FIELD}. */
    public static final String METRIC_NAMES_SOURCE = "run_metric_snapshots";

    /**
     * Extension-only result key carrying the run's latest computation's run-level {@code overall}
     * metric score (see {@code OverallScoreTestSuiteRunsPageExtender}). It is merged onto a row after
     * SQL and after paging, from the analytics {@code metric_score_results} entity, and is
     * deliberately absent from both {@link PostgresTestSuiteRunEntityResolver#bindings} and
     * {@link TestSuiteRunsSchemaProvider}'s published schema: it is not a queryable field and MUST NOT
     * be accepted in {@code filter}/{@code select}/{@code sort}/{@code group_by}.
     */
    public static final String OVERALL_SCORE_VALUE_FIELD = "overall_score_value";

    /**
     * Extension-only result key carrying a run's total dial-adas usage cost (see
     * {@code TotalCostTestSuiteRunsPageExtender}), only present when
     * {@code query-dsl.extension.test-suite-run.cost.enabled} is {@code true}. It is merged onto a row
     * after SQL and after paging, from one page-bounded dial-adas aggregate lookup, and is deliberately
     * absent from both {@link PostgresTestSuiteRunEntityResolver#bindings} and
     * {@link TestSuiteRunsSchemaProvider}'s published schema: it is not a queryable field and MUST NOT
     * be accepted in {@code filter}/{@code select}/{@code sort}/{@code group_by}.
     */
    public static final String TOTAL_COST_FIELD = "total_cost";

    public static final RefDescriptor DEPLOYMENT_REF =
            new RefDescriptor("deployment_ref", "deploymentRef", List.of("id", "name", "version", "type"));

    public static final RefDescriptor MCP_DEPLOYMENT_REF =
            new RefDescriptor("mcp_deployment_ref", "mcpDeploymentRef", List.of("id", "name", "type", "transport"));

    /** The two snapshot-backed reference descriptors, in publication order. */
    public static final List<RefDescriptor> REF_DESCRIPTORS = List.of(DEPLOYMENT_REF, MCP_DEPLOYMENT_REF);

    private TestSuiteRunQueryFields() {}

    /** Builds a sub-field name, e.g. {@code subField("deployment_ref", "id") == "deployment_ref::id"}. */
    public static String subField(String fieldPrefix, String subKey) {
        return fieldPrefix + FIELD_SEPARATOR + subKey;
    }

    /**
     * A {@code suite_snapshot}-backed reference: sub-fields {@code <fieldPrefix>::<subKey>} for each of
     * {@code subKeys}, each extracted from {@code suite_snapshot -> snapshotKey ->> subKey}.
     */
    public record RefDescriptor(String fieldPrefix, String snapshotKey, List<String> subKeys) {}
}
