package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasBatchRunCostRowDto;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end coverage of the {@code total_cost} row-page extension (design D1/D3/D4 of
 * {@code enrich-test-suite-runs-total-cost}) with {@code
 * query-dsl.extension.test-suite-run.cost.enabled=true}. The concrete nested registration in {@code
 * PostgresFunctionalTests} carries the {@code @TestPropertySource} enabling override, which boots a
 * separate application context with the qualified {@code testSuiteRunCostExtensionExecutor} /
 * {@code testSuiteRunCostExtensionDialAdasClient} beans registered (see {@code
 * TestSuiteRunCostExtensionAsyncConfiguration} / {@code DialAdasClientConfiguration}); this class's
 * fields, including the named {@link MockitoBean}, live here rather than on the trivial nested
 * subclass, matching the established pattern of the other multi-field structured-query functional test
 * classes (e.g. {@link TestSuiteRunStructuredQueryFunctionalTests}) that keep all fixtures/tests on the
 * abstract base.
 *
 * <p>The by-type {@code @MockitoBean DialAdasClient} declared on the outer {@code
 * PostgresFunctionalTests} class still overrides the {@code @Primary} shared client in this
 * separately-booted context (nested test classes inherit outer class-level bean overrides), so
 * {@link #dialAdasClient} here is that same shared-client mock, distinct from
 * {@link #testSuiteRunCostExtensionDialAdasClient}.
 */
@DisplayName("test_suite_runs total_cost extension (enabled)")
public abstract class TestSuiteRunCostExtensionFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private StructuredQueryExecutor queryRepository;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    /** The normal, shared, by-type mocked client (outer {@code PostgresFunctionalTests} field). */
    @Autowired
    private DialAdasClient dialAdasClient;

    /** The dedicated, short-timeout extension client, mocked by name for this scenario only. */
    @MockitoBean(name = "testSuiteRunCostExtensionDialAdasClient")
    private DialAdasClient testSuiteRunCostExtensionDialAdasClient;

    @Autowired
    @Qualifier("testSuiteRunCostExtensionExecutor")
    private AsyncTaskExecutor testSuiteRunCostExtensionExecutor;

    private static StructuredQuery rowQuery(UUID runId) {
        FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("id"), new ValueExpr(ValueType.UUID, runId.toString())));
        return new StructuredQuery(
                TestSuiteRunQueryFields.ENTITY,
                filter,
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("id"), null)),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));
    }

    private static AdasAggregateResponseDto<AdasBatchRunCostRowDto> costResponse(UUID runId, double totalCost) {
        return AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                .rows(List.of(AdasBatchRunCostRowDto.builder()
                        .runId(runId.toString())
                        .totalCost(totalCost)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("a row with a mocked grouped ADAS total gets total_cost")
    void attachesTotalCostForRowWithMatchingUsageGroup() {
        TestSuite suite = metaTestDataHelper.createTestSuite("cost-ext-match-" + UUID.randomUUID());
        TestSuiteRun run = metaTestDataHelper.createTestSuiteRun(suite.getId());
        when(testSuiteRunCostExtensionDialAdasClient.executeAggregate(
                        any(StructuredQuery.class), eq(AdasBatchRunCostRowDto.class)))
                .thenReturn(costResponse(run.getId(), 12.5));

        QueryResultPage page = queryRepository.execute(rowQuery(run.getId()));

        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().get(0);
        assertThat(row.get(TestSuiteRunQueryFields.TOTAL_COST_FIELD)).isEqualTo(12.5);
    }

    @Test
    @DisplayName("a missing usage group omits the total_cost key rather than setting it to null")
    void omitsTotalCostWhenGroupMissing() {
        TestSuite suite = metaTestDataHelper.createTestSuite("cost-ext-missing-" + UUID.randomUUID());
        TestSuiteRun run = metaTestDataHelper.createTestSuiteRun(suite.getId());
        when(testSuiteRunCostExtensionDialAdasClient.executeAggregate(
                        any(StructuredQuery.class), eq(AdasBatchRunCostRowDto.class)))
                .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                        .rows(List.of())
                        .build());

        QueryResultPage page = queryRepository.execute(rowQuery(run.getId()));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0)).doesNotContainKey(TestSuiteRunQueryFields.TOTAL_COST_FIELD);
    }

    @Test
    @DisplayName("one eligible page issues exactly one aggregate call with no eval.phase filter, and zero calls on"
            + " the primary shared client")
    void issuesExactlyOneAggregateCallWithNoEvalPhaseFilterAndNoPrimaryCalls() {
        TestSuite suite = metaTestDataHelper.createTestSuite("cost-ext-onecall-" + UUID.randomUUID());
        TestSuiteRun run = metaTestDataHelper.createTestSuiteRun(suite.getId());
        when(testSuiteRunCostExtensionDialAdasClient.executeAggregate(
                        any(StructuredQuery.class), eq(AdasBatchRunCostRowDto.class)))
                .thenReturn(costResponse(run.getId(), 3.0));

        queryRepository.execute(rowQuery(run.getId()));

        ArgumentCaptor<StructuredQuery> captor = ArgumentCaptor.forClass(StructuredQuery.class);
        verify(testSuiteRunCostExtensionDialAdasClient, times(1))
                .executeAggregate(captor.capture(), eq(AdasBatchRunCostRowDto.class));
        assertThat(captor.getValue().toString()).doesNotContain("eval.phase");
        verifyNoInteractions(dialAdasClient);
    }

    @Test
    @DisplayName("a query with no eligible ids (empty page) performs zero extension calls")
    void noEligibleIdsMakeZeroExtensionCalls() {
        QueryResultPage page = queryRepository.execute(rowQuery(UUID.randomUUID()));

        assertThat(page.rows()).isEmpty();
        verifyNoInteractions(testSuiteRunCostExtensionDialAdasClient);
    }

    @Test
    @DisplayName("the enabled context boots with the qualified extension client and executor wired")
    void enabledContextBootsWithQualifiedClientAndExecutorWiring() {
        assertThat(testSuiteRunCostExtensionDialAdasClient).isNotNull();
        assertThat(testSuiteRunCostExtensionExecutor).isNotNull().isInstanceOf(SimpleAsyncTaskExecutor.class);
    }
}
