package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.RUN_METRIC_SNAPSHOTS;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_RUNS;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.EXCLUDED_COLUMNS;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.METRIC_NAMES_FIELD;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.REF_DESCRIPTORS;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.SUITE_TYPE_FIELD;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.SUITE_TYPE_SNAPSHOT_KEY;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.subField;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.RunMetricSnapshots;
import com.epam.aidial.evaluation.data.db.repository.sql.json.JsonPathAccessor;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields;
import com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.RefDescriptor;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the {@code test_suite_runs} entity to a derived table over {@code TEST_SUITE_RUNS} on the
 * meta datasource ({@code metaDsl}). The derived table ({@code tsr}) projects exactly the fields of
 * {@link TestSuiteRunQueryFields}: the run's own plain columns (minus the heavy/opaque
 * {@link TestSuiteRunQueryFields#EXCLUDED_COLUMNS}), the {@code suite_snapshot}-backed
 * {@code suite_type}/{@code deployment_ref::*}/{@code mcp_deployment_ref::*} text extractions, and a
 * correlated {@code metric_names} scalar subquery over {@code run_metric_snapshots} restricted to the
 * run's latest computation. Only a derived table can make the row-mode "empty select" projection
 * (which selects {@code table().fields()}) omit {@code suite_snapshot}/{@code run_config}/
 * {@code error_details}; Postgres pulls the derived table up into the outer query, so filters on
 * plain columns still hit {@code test_suite_runs}' own indexes.
 *
 * <p>The derived table and its bindings are static per-table metadata, computed once here; both
 * are immutable jOOQ expression trees safely reused across concurrent queries.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestSuiteRunEntityResolver implements StructuredQueryEntityResolver {

    private static final String TABLE_ALIAS = "tsr";
    private static final String RMS_ALIAS = "rms";
    private static final String LATEST_ALIAS = "latest";

    private final DSLContext dsl;
    private final Table<?> table;
    private final Map<String, QueryFieldBinding> bindings;

    public PostgresTestSuiteRunEntityResolver(
            @Qualifier("metaDsl") DSLContext dsl,
            JooqTableSchemaResolver schemaResolver,
            JsonPathAccessor jsonPathAccessor) {
        this.dsl = dsl;
        this.table = buildTable(jsonPathAccessor);
        this.bindings = buildBindings(schemaResolver, table);
    }

    @Override
    public String entity() {
        return TestSuiteRunQueryFields.ENTITY;
    }

    @Override
    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public Table<?> table() {
        return table;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }

    private static Table<?> buildTable(JsonPathAccessor jsonPathAccessor) {
        final List<Field<?>> projection = new ArrayList<>();
        for (final Field<?> column : TEST_SUITE_RUNS.fields()) {
            if (!EXCLUDED_COLUMNS.contains(column.getName())) {
                projection.add(column);
            }
        }
        projection.add(jsonPathAccessor
                .jsonbAtAsText(TEST_SUITE_RUNS.SUITE_SNAPSHOT, DSL.val(SUITE_TYPE_SNAPSHOT_KEY))
                .as(DSL.name(SUITE_TYPE_FIELD)));
        projection.addAll(buildRefFields(jsonPathAccessor));
        projection.add(buildMetricNamesField());
        return DSL.select(projection).from(TEST_SUITE_RUNS).asTable(TABLE_ALIAS);
    }

    private static List<Field<String>> buildRefFields(JsonPathAccessor jsonPathAccessor) {
        final List<Field<String>> refFields = new ArrayList<>();
        for (final RefDescriptor ref : REF_DESCRIPTORS) {
            final Field<JSONB> refJson =
                    jsonPathAccessor.jsonbAt(TEST_SUITE_RUNS.SUITE_SNAPSHOT, DSL.val(ref.snapshotKey()));
            for (final String subKey : ref.subKeys()) {
                refFields.add(jsonPathAccessor
                        .jsonbAtAsText(refJson, DSL.val(subKey))
                        .as(DSL.name(subField(ref.fieldPrefix(), subKey))));
            }
        }
        return refFields;
    }

    private static Field<JSONB> buildMetricNamesField() {
        final RunMetricSnapshots rms = RUN_METRIC_SNAPSHOTS.as(RMS_ALIAS);
        final RunMetricSnapshots latest = RUN_METRIC_SNAPSHOTS.as(LATEST_ALIAS);

        final Field<String> latestComputationId = DSL.field(DSL.select(latest.COMPUTATION_ID)
                .from(latest)
                .where(latest.TEST_SUITE_RUN_ID.eq(TEST_SUITE_RUNS.ID))
                .orderBy(latest.COMPUTED_AT_MS.desc(), latest.COMPUTATION_ID.desc())
                .limit(1));

        final Field<JSONB> metricNames = DSL.field(DSL.select(DSL.coalesce(
                                DSL.jsonbArrayAggDistinct(rms.TSMD_NAME).orderBy(rms.TSMD_NAME),
                                DSL.inline(JSONB.valueOf("[]"))))
                        .from(rms)
                        .where(rms.TEST_SUITE_RUN_ID.eq(TEST_SUITE_RUNS.ID))
                        .and(rms.COMPUTATION_ID.eq(latestComputationId)))
                .as(DSL.name(METRIC_NAMES_FIELD));
        return metricNames;
    }

    private static Map<String, QueryFieldBinding> buildBindings(JooqTableSchemaResolver schemaResolver, Table<?> tsr) {
        final Map<String, QueryFieldBinding> baseTypes = schemaResolver.bindings(TEST_SUITE_RUNS);
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();

        for (final Field<?> column : TEST_SUITE_RUNS.fields()) {
            final String name = column.getName();
            if (EXCLUDED_COLUMNS.contains(name)) {
                continue;
            }
            final QueryFieldType type = baseTypes.get(name).type();
            bindings.put(name, new QueryFieldBinding(name, requireField(tsr, name), type));
        }

        bindings.put(SUITE_TYPE_FIELD, stringBinding(tsr, SUITE_TYPE_FIELD));
        for (final RefDescriptor ref : REF_DESCRIPTORS) {
            for (final String subKey : ref.subKeys()) {
                final String fieldName = subField(ref.fieldPrefix(), subKey);
                bindings.put(fieldName, stringBinding(tsr, fieldName));
            }
        }

        bindings.put(
                METRIC_NAMES_FIELD,
                new QueryFieldBinding(METRIC_NAMES_FIELD, requireField(tsr, METRIC_NAMES_FIELD), QueryFieldType.ARRAY));

        return Map.copyOf(bindings);
    }

    private static QueryFieldBinding stringBinding(Table<?> tsr, String fieldName) {
        return new QueryFieldBinding(fieldName, requireField(tsr, fieldName), QueryFieldType.STRING);
    }

    private static Field<?> requireField(Table<?> tsr, String name) {
        return Objects.requireNonNull(tsr.field(name), () -> "Derived table tsr has no column " + name);
    }
}
