package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.OffsetPage;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.experimental.query.service.repository.StructuredQueryExecutor;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Structured Query → jOOQ translation (test_suites) Tests")
public abstract class TestSuiteStructuredQueryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private StructuredQueryExecutor queryRepository;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    private static StructuredQuery rowQuery(FilterNode filter, List<OutputColumn> select) {
        return new StructuredQuery(
                "test_suites", filter, QueryMode.ROW, false, select, null, null, null, new OffsetPage(0, 100, false));
    }

    private static OutputColumn col(Expr expr) {
        return new OutputColumn(expr, null);
    }

    private static ComparisonNode eq(String field, ValueType type, String value) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(field), new ValueExpr(type, value)));
    }

    @Test
    @DisplayName("filters test_suites by an exact name and projects the matching row")
    void filtersByName() {
        String prefix = "sq-" + UUID.randomUUID();
        TestSuite target = metaTestDataHelper.createTestSuite(prefix + "-a");
        metaTestDataHelper.createTestSuite(prefix + "-b");

        QueryResultPage page = queryRepository.execute(rowQuery(
                eq("name", ValueType.STRING, prefix + "-a"),
                List.of(col(new FieldExpr("id")), col(new FieldExpr("name")))));

        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().get(0);
        assertThat(row).containsOnlyKeys("id", "name");
        assertThat(row.get("id")).isEqualTo(target.getId().toString());
        assertThat(row.get("name")).isEqualTo(prefix + "-a");
    }

    @Test
    @DisplayName("translates 'in' into an IN list matching multiple suites")
    void filtersByInList() {
        String prefix = "sq-" + UUID.randomUUID();
        metaTestDataHelper.createTestSuite(prefix + "-a");
        metaTestDataHelper.createTestSuite(prefix + "-b");
        metaTestDataHelper.createTestSuite(prefix + "-c");

        FilterNode filter = new ComparisonNode(
                ComparisonOp.IN,
                List.of(
                        new FieldExpr("name"),
                        new ArrayExpr(List.of(
                                new ValueExpr(ValueType.STRING, prefix + "-a"),
                                new ValueExpr(ValueType.STRING, prefix + "-c")))));

        QueryResultPage page = queryRepository.execute(rowQuery(filter, List.of(col(new FieldExpr("name")))));

        assertThat(page.rows())
                .extracting(row -> row.get("name"))
                .containsExactlyInAnyOrder(prefix + "-a", prefix + "-c");
    }

    @Test
    @DisplayName("counts matching suites in aggregate mode with count(*)")
    void aggregatesCount() {
        String prefix = "sq-" + UUID.randomUUID();
        metaTestDataHelper.createTestSuite(prefix + "-a");
        metaTestDataHelper.createTestSuite(prefix + "-b");
        metaTestDataHelper.createTestSuite(prefix + "-c");

        FilterNode filter = new ComparisonNode(
                ComparisonOp.CO, List.of(new FieldExpr("name"), new ValueExpr(ValueType.STRING, prefix)));
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                filter,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FnExpr("count", false, List.of()), "total")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1);
        assertThat(((Number) page.rows().get(0).get("total")).intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("populates totalCount when offset paging requests include_total")
    void populatesTotalCount() {
        String prefix = "sq-" + UUID.randomUUID();
        metaTestDataHelper.createTestSuite(prefix + "-a");
        metaTestDataHelper.createTestSuite(prefix + "-b");

        FilterNode filter = new ComparisonNode(
                ComparisonOp.CO, List.of(new FieldExpr("name"), new ValueExpr(ValueType.STRING, prefix)));
        StructuredQuery query = new StructuredQuery(
                "test_suites", filter, QueryMode.ROW, false, null, null, null, null, new OffsetPage(0, 1, true));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1); // limited to 1
        assertThat(page.totalCount()).isEqualTo(2L); // but total reflects all matches
    }

    @Test
    @DisplayName("executes count/min/max aggregates over a filtered set in one row")
    void aggregatesNumericFunctions() {
        String prefix = "sq-" + UUID.randomUUID();
        metaTestDataHelper.createTestSuite(prefix + "-a");
        metaTestDataHelper.createTestSuite(prefix + "-b");
        metaTestDataHelper.createTestSuite(prefix + "-c");

        FilterNode filter = new ComparisonNode(
                ComparisonOp.CO, List.of(new FieldExpr("name"), new ValueExpr(ValueType.STRING, prefix)));
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                filter,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FnExpr("count", false, List.of()), "total"),
                        new OutputColumn(new FnExpr("min", false, List.of(new FieldExpr("created_at_ms"))), "earliest"),
                        new OutputColumn(new FnExpr("max", false, List.of(new FieldExpr("created_at_ms"))), "latest")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().get(0);
        assertThat(((Number) row.get("total")).intValue()).isEqualTo(3);
        long earliest = ((Number) row.get("earliest")).longValue();
        long latest = ((Number) row.get("latest")).longValue();
        assertThat(earliest).isLessThanOrEqualTo(latest);
    }

    @Test
    @DisplayName("rejects a query targeting an unsupported entity")
    void rejectsUnsupportedEntity() {
        // "eval_summaries" is itself a valid, registered entity (just not this test's focus), so the
        // shared, entity-agnostic executor would route it successfully rather than reject it; use an
        // entity name that has no registered resolver anywhere to exercise the true rejection path.
        StructuredQuery query = new StructuredQuery(
                "not_a_real_entity", null, QueryMode.ROW, false, null, null, null, null, new OffsetPage(0, 10, false));

        assertThatThrownBy(() -> queryRepository.execute(query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not_a_real_entity");
    }

    @Test
    @DisplayName("filters test_suites by deployment_ref::name using JSONB text extraction")
    void filtersByDeploymentRefName() {
        String prefix = "sq-depref-" + UUID.randomUUID();
        String deploymentRefJson =
                "{\"id\":\"my-app-id\",\"name\":\"My App\",\"version\":\"1.0\",\"type\":\"dial-application\"}";
        TestSuite target = metaTestDataHelper.createTestSuiteWithDeploymentRef(prefix + "-target", deploymentRefJson);
        TestSuite noRef = metaTestDataHelper.createTestSuite(prefix + "-no-ref");

        QueryResultPage page = queryRepository.execute(rowQuery(
                eq("deployment_ref::name", ValueType.STRING, "My App"),
                List.of(col(new FieldExpr("id")), col(new FieldExpr("name")))));

        assertThat(page.rows())
                .extracting(row -> row.get("id"))
                .contains(target.getId().toString())
                .doesNotContain(noRef.getId().toString());
    }
}
