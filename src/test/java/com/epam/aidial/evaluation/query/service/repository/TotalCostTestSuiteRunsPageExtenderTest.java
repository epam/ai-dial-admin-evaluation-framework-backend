package com.epam.aidial.evaluation.query.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration;
import com.epam.aidial.evaluation.configuration.properties.query.QueryDslTestSuiteRunCostExtensionProperties;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.service.domain.AdasCostQueryBuilder;
import com.epam.aidial.evaluation.service.domain.BatchRunTotalCostLookup;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("TotalCostTestSuiteRunsPageExtender")
class TotalCostTestSuiteRunsPageExtenderTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final BatchRunTotalCostLookup batchRunTotalCostLookup = mock(BatchRunTotalCostLookup.class);
    private final DialAdasClient dialAdasClient = mock(DialAdasClient.class);

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    private static QueryDslTestSuiteRunCostExtensionProperties properties(int timeoutSec) {
        final QueryDslTestSuiteRunCostExtensionProperties properties =
                new QueryDslTestSuiteRunCostExtensionProperties();
        properties.setEnabled(true);
        properties.setTimeoutSec(timeoutSec);
        return properties;
    }

    private TotalCostTestSuiteRunsPageExtender extender(
            BatchRunTotalCostLookup lookup, DialAdasClient client, AsyncTaskExecutor executor, int timeoutSec) {
        return new TotalCostTestSuiteRunsPageExtender(lookup, client, executor, properties(timeoutSec));
    }

    private TotalCostTestSuiteRunsPageExtender extender(
            BatchRunTotalCostLookup lookup, AsyncTaskExecutor executor, int timeoutSec) {
        return extender(lookup, dialAdasClient, executor, timeoutSec);
    }

    private TotalCostTestSuiteRunsPageExtender defaultExtender() {
        return extender(batchRunTotalCostLookup, new SimpleAsyncTaskExecutor(), 2);
    }

    private static StructuredQuery rowQuery() {
        return rowQuery(List.of());
    }

    private static StructuredQuery rowQuery(List<OutputColumn> select) {
        return new StructuredQuery(
                TestSuiteRunQueryFields.ENTITY, null, QueryMode.ROW, false, select, null, null, null, null);
    }

    private static OutputColumn col(String field, String as) {
        return new OutputColumn(new FieldExpr(field), as);
    }

    private static Map<String, Object> row(String id, String extraKey, Object extraValue) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put(extraKey, extraValue);
        return row;
    }

    private static QueryResultPage pageWithRow(Map<String, Object> row) {
        return new QueryResultPage(List.of(row), 1L);
    }

    // ---- 3.2: applicability, id derivation, one batch call, merge mechanics ----

    @Test
    @DisplayName("an eligible run's row carries its total cost, from exactly one batch call with no other args")
    void attachesTotalCostWithOneBatchCall() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        when(batchRunTotalCostLookup.fetchTotalCosts(List.of(RUN_ID), dialAdasClient))
                .thenReturn(Map.of(RUN_ID, 1.25));

        final QueryResultPage result = defaultExtender().extend(rowQuery(), page);

        final Map<String, Object> extendedRow = result.rows().get(0);
        assertThat(extendedRow).containsEntry("id", RUN_ID.toString()).containsEntry("existing", "value");
        assertThat(extendedRow.get(TestSuiteRunQueryFields.TOTAL_COST_FIELD)).isEqualTo(1.25);
        assertThat(result.totalCount()).isEqualTo(page.totalCount());
        assertThat(List.copyOf(extendedRow.keySet()))
                .containsExactly("id", "existing", TestSuiteRunQueryFields.TOTAL_COST_FIELD);
        verify(batchRunTotalCostLookup, times(1)).fetchTotalCosts(List.of(RUN_ID), dialAdasClient);
    }

    @Test
    @DisplayName("uses the qualified extension dial-adas client passed at construction, never another instance")
    void usesTheQualifiedDialAdasClient() {
        final DialAdasClient otherClient = mock(DialAdasClient.class);
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        when(batchRunTotalCostLookup.fetchTotalCosts(any(), eq(dialAdasClient))).thenReturn(Map.of(RUN_ID, 2.0));

        defaultExtender().extend(rowQuery(), page);

        verify(batchRunTotalCostLookup).fetchTotalCosts(any(), eq(dialAdasClient));
        verifyNoInteractions(otherClient);
    }

    @Test
    @DisplayName("a run with no matching dial-adas usage group omits the key")
    void omitsKeyWhenNoUsageMatches() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        when(batchRunTotalCostLookup.fetchTotalCosts(List.of(RUN_ID), dialAdasClient))
                .thenReturn(Map.of());

        final QueryResultPage result = defaultExtender().extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        assertThat(result.rows().get(0)).doesNotContainKey(TestSuiteRunQueryFields.TOTAL_COST_FIELD);
    }

    @Test
    @DisplayName("a query targeting another entity is untouched, with zero dial-adas calls")
    void skipsOtherEntity() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        final StructuredQuery otherEntityQuery =
                new StructuredQuery("test_suites", null, QueryMode.ROW, false, List.of(), null, null, null, null);

        final QueryResultPage result = defaultExtender().extend(otherEntityQuery, page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(batchRunTotalCostLookup, dialAdasClient);
    }

    @Test
    @DisplayName("aggregate mode is untouched, with zero dial-adas calls")
    void skipsAggregateMode() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        final StructuredQuery aggregateQuery = new StructuredQuery(
                TestSuiteRunQueryFields.ENTITY, null, QueryMode.AGGREGATE, false, List.of(), null, null, null, null);

        final QueryResultPage result = defaultExtender().extend(aggregateQuery, page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(batchRunTotalCostLookup, dialAdasClient);
    }

    @Test
    @DisplayName("an empty page is untouched, with zero dial-adas calls")
    void skipsEmptyPage() {
        final QueryResultPage page = new QueryResultPage(List.of(), 0L);

        final QueryResultPage result = defaultExtender().extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(batchRunTotalCostLookup, dialAdasClient);
    }

    @Test
    @DisplayName("an explicit projection keeping id under the id key is extended")
    void extendsExplicitProjectionWithId() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "test_run_name", "run-1"));
        when(batchRunTotalCostLookup.fetchTotalCosts(List.of(RUN_ID), dialAdasClient))
                .thenReturn(Map.of(RUN_ID, 1.25));

        final QueryResultPage result =
                defaultExtender().extend(rowQuery(List.of(col("id", null), col("test_run_name", null))), page);

        assertThat(result.rows().get(0)).containsEntry(TestSuiteRunQueryFields.TOTAL_COST_FIELD, 1.25);
    }

    @Test
    @DisplayName("a projection without id is untouched, with zero dial-adas calls")
    void skipsProjectionWithoutId() {
        final Map<String, Object> rowWithoutId = new LinkedHashMap<>();
        rowWithoutId.put("test_run_name", "run-1");
        final QueryResultPage page = new QueryResultPage(List.of(rowWithoutId), 1L);

        final QueryResultPage result = defaultExtender().extend(rowQuery(List.of(col("test_run_name", null))), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(batchRunTotalCostLookup, dialAdasClient);
    }

    @Test
    @DisplayName("a projection renaming id to another key is untouched, with zero dial-adas calls")
    void skipsProjectionRenamingId() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("run", RUN_ID.toString());
        final QueryResultPage page = new QueryResultPage(List.of(row), 1L);

        final QueryResultPage result = defaultExtender().extend(rowQuery(List.of(col("id", "run"))), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(batchRunTotalCostLookup, dialAdasClient);
    }

    @Test
    @DisplayName("a projection aliasing another field as id is untouched, with zero dial-adas calls")
    void skipsProjectionAliasingOtherFieldAsId() {
        final QueryResultPage page = pageWithRow(row("run-1", "status", "COMPLETED"));

        final QueryResultPage result =
                defaultExtender().extend(rowQuery(List.of(col("test_run_name", "id"), col("status", null))), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(batchRunTotalCostLookup, dialAdasClient);
    }

    @Test
    @DisplayName(
            "a projection already carrying the derived key via an alias is untouched, with zero dial-adas" + " calls")
    void skipsProjectionAlreadyCarryingKey() {
        final Map<String, Object> row = row(RUN_ID.toString(), TestSuiteRunQueryFields.TOTAL_COST_FIELD, "run-1");
        final QueryResultPage page = new QueryResultPage(List.of(row), 1L);

        final QueryResultPage result = defaultExtender()
                .extend(
                        rowQuery(List.of(
                                col("id", null), col("test_run_name", TestSuiteRunQueryFields.TOTAL_COST_FIELD))),
                        page);

        assertThat(result).isSameAs(page);
        assertThat(result.rows().get(0)).containsEntry(TestSuiteRunQueryFields.TOTAL_COST_FIELD, "run-1");
        verifyNoInteractions(batchRunTotalCostLookup, dialAdasClient);
    }

    @Test
    @DisplayName(
            "on a multi-row page, only rows with a cost gain the key; row/key order and totalCount are" + " unchanged")
    void extendsOnlyCostedRowsAndPreservesOrder() {
        final Map<String, Object> uncostedRow = row(RUN_ID.toString(), "existing", "value");
        final Map<String, Object> costedRow = row(RUN_ID_2.toString(), "existing", "value");
        final QueryResultPage page = new QueryResultPage(List.of(uncostedRow, costedRow), 2L);
        when(batchRunTotalCostLookup.fetchTotalCosts(List.of(RUN_ID, RUN_ID_2), dialAdasClient))
                .thenReturn(Map.of(RUN_ID_2, 3.5));

        final QueryResultPage result = defaultExtender().extend(rowQuery(), page);

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).isSameAs(uncostedRow);
        assertThat(result.rows().get(1).get("id")).isEqualTo(RUN_ID_2.toString());
        assertThat(result.rows().get(1).get(TestSuiteRunQueryFields.TOTAL_COST_FIELD))
                .isEqualTo(3.5);
        assertThat(result.totalCount()).isEqualTo(2L);
        assertThat(List.copyOf(result.rows().get(1).keySet()))
                .containsExactly("id", "existing", TestSuiteRunQueryFields.TOTAL_COST_FIELD);
    }

    // ---- 3.3: caller credential + OTel context propagation across the submission boundary ----

    @Test
    @DisplayName("the async lookup carries the caller's bearer credential to the dial-adas request")
    void propagatesBearerCredential() {
        final RestClient.Builder builder = RestClient.builder().baseUrl("http://dial-adas.local");
        final MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        final RestClient restClient = builder.requestInterceptor(
                        DialCoreClientConfiguration.callerCredentialInterceptor())
                .build();
        final DialAdasClient realClient = new DialAdasClient(restClient);
        final BatchRunTotalCostLookup realLookup = new BatchRunTotalCostLookup(new AdasCostQueryBuilder());
        server.expect(requestTo("http://dial-adas.local/v1/queries/execute"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-123"))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("tok-123"));
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));

        final QueryResultPage result = extender(realLookup, realClient, new SimpleAsyncTaskExecutor(), 2)
                .extend(rowQuery(), page);

        server.verify();
        // Empty dial-adas response yields no matching group, so the page comes back unchanged; the
        // header assertion above is this test's real point.
        assertThat(result).isSameAs(page);
    }

    @Test
    @DisplayName("the async lookup carries the caller's api-key credential (never as a bearer token)")
    void propagatesApiKeyCredential() {
        final RestClient.Builder builder = RestClient.builder().baseUrl("http://dial-adas.local");
        final MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        final RestClient restClient = builder.requestInterceptor(
                        DialCoreClientConfiguration.callerCredentialInterceptor())
                .build();
        final DialAdasClient realClient = new DialAdasClient(restClient);
        final BatchRunTotalCostLookup realLookup = new BatchRunTotalCostLookup(new AdasCostQueryBuilder());
        server.expect(requestTo("http://dial-adas.local/v1/queries/execute"))
                .andExpect(header(CallerCredential.API_KEY_HEADER, "key-123"))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("key-123"));

        extender(realLookup, realClient, new SimpleAsyncTaskExecutor(), 2)
                .extend(rowQuery(), pageWithRow(row(RUN_ID.toString(), "existing", "value")));

        server.verify();
    }

    @Test
    @DisplayName("the async lookup remains part of the caller's OpenTelemetry context (baggage propagates)")
    void propagatesOtelBaggage() {
        final OpenTelemetry openTelemetry = OpenTelemetry.propagating(
                ContextPropagators.create(TextMapPropagator.composite(W3CBaggagePropagator.getInstance())));
        final RestClient.Builder builder = RestClient.builder().baseUrl("http://dial-adas.local");
        final MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        final RestClient restClient = builder.requestInterceptor(
                        DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .build();
        final DialAdasClient realClient = new DialAdasClient(restClient);
        final BatchRunTotalCostLookup realLookup = new BatchRunTotalCostLookup(new AdasCostQueryBuilder());
        server.expect(requestTo("http://dial-adas.local/v1/queries/execute"))
                .andExpect(request ->
                        assertThat(request.getHeaders().getFirst("baggage")).contains("case-key=case-value"))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));
        final Baggage baggage = Baggage.builder().put("case-key", "case-value").build();

        try (Scope scope = baggage.storeInContext(Context.root()).makeCurrent()) {
            extender(realLookup, realClient, new SimpleAsyncTaskExecutor(), 2)
                    .extend(rowQuery(), pageWithRow(row(RUN_ID.toString(), "existing", "value")));
        }

        server.verify();
    }

    @Test
    @DisplayName("the propagated credential is cleared after the lookup: a later task without one sees none")
    void clearsCredentialAfterLookupOnReusedThread() throws Exception {
        final ExecutorService singleThreadPool = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentTaskExecutor executor = new ConcurrentTaskExecutor(singleThreadPool);
            when(batchRunTotalCostLookup.fetchTotalCosts(any(), any())).thenReturn(Map.of());
            AuthorizationTokenHolder.setCredential(CallerCredential.bearer("tok-first"));

            extender(batchRunTotalCostLookup, executor, 5)
                    .extend(rowQuery(), pageWithRow(row(RUN_ID.toString(), "x", "y")));

            AuthorizationTokenHolder.clearToken();
            final AtomicReference<CallerCredential> seenOnSecondCall = new AtomicReference<>();
            when(batchRunTotalCostLookup.fetchTotalCosts(any(), any())).thenAnswer(invocation -> {
                seenOnSecondCall.set(AuthorizationTokenHolder.getCredential());
                return Map.of();
            });

            extender(batchRunTotalCostLookup, executor, 5)
                    .extend(rowQuery(), pageWithRow(row(RUN_ID_2.toString(), "x", "y")));

            assertThat(seenOnSecondCall.get()).isNull();
        } finally {
            singleThreadPool.shutdownNow();
        }
    }

    // ---- 3.4: authoritative Future.get deadline and degrade-once failure handling ----

    @Test
    @DisplayName("a lookup exceeding timeoutSec is cancelled (interrupted) and the page is returned unchanged")
    void timesOutCancelsLookupAndReturnsPageUnchanged() throws Exception {
        final CountDownLatch lookupStarted = new CountDownLatch(1);
        final CountDownLatch releaseLookup = new CountDownLatch(1);
        final CompletableFuture<Boolean> lookupInterrupted = new CompletableFuture<>();
        when(batchRunTotalCostLookup.fetchTotalCosts(any(), any())).thenAnswer(invocation -> {
            lookupStarted.countDown();
            try {
                releaseLookup.await(5, TimeUnit.SECONDS);
                lookupInterrupted.complete(false);
            } catch (InterruptedException e) {
                lookupInterrupted.complete(true);
            }
            return Map.of(RUN_ID, 1.0);
        });
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));

        final QueryResultPage result = extender(batchRunTotalCostLookup, new SimpleAsyncTaskExecutor(), 1)
                .extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        assertThat(result.rows().get(0)).doesNotContainKey(TestSuiteRunQueryFields.TOTAL_COST_FIELD);
        assertThat(lookupStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(lookupInterrupted.get(3, TimeUnit.SECONDS)).isTrue();
        releaseLookup.countDown();
    }

    @Test
    @DisplayName("a lookup that throws degrades to the unchanged page (ExecutionException)")
    void executionFailureReturnsPageUnchanged() {
        when(batchRunTotalCostLookup.fetchTotalCosts(any(), any()))
                .thenThrow(new IllegalStateException("dial-adas boom"));
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));

        final QueryResultPage result = extender(batchRunTotalCostLookup, new SimpleAsyncTaskExecutor(), 2)
                .extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
    }

    @Test
    @DisplayName(
            "a submission rejected by a closed extension executor returns the page unchanged, with no" + " lookup call")
    void rejectedSubmissionReturnsPageUnchanged() {
        final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.close();
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));

        final QueryResultPage result =
                extender(batchRunTotalCostLookup, executor, 2).extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(batchRunTotalCostLookup);
    }

    @Test
    @DisplayName("interrupting the caller while waiting cancels the lookup, restores the interrupt status, and"
            + " returns the page unchanged (no partial merge)")
    void interruptedCallerCancelsLookupAndRestoresInterruptStatus() throws Exception {
        final CountDownLatch lookupStarted = new CountDownLatch(1);
        final CountDownLatch releaseLookup = new CountDownLatch(1);
        final CompletableFuture<Boolean> lookupInterrupted = new CompletableFuture<>();
        when(batchRunTotalCostLookup.fetchTotalCosts(any(), any())).thenAnswer(invocation -> {
            lookupStarted.countDown();
            try {
                releaseLookup.await(5, TimeUnit.SECONDS);
                lookupInterrupted.complete(false);
            } catch (InterruptedException e) {
                lookupInterrupted.complete(true);
            }
            return Map.of(RUN_ID, 1.0);
        });
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        final TotalCostTestSuiteRunsPageExtender ext =
                extender(batchRunTotalCostLookup, new SimpleAsyncTaskExecutor(), 10);
        final AtomicReference<QueryResultPage> resultRef = new AtomicReference<>();
        final AtomicBoolean callerInterruptedAfterReturn = new AtomicBoolean(false);
        final Thread caller = new Thread(() -> {
            resultRef.set(ext.extend(rowQuery(), page));
            callerInterruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        });

        caller.start();
        assertThat(lookupStarted.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(3000);

        assertThat(resultRef.get()).isSameAs(page);
        assertThat(callerInterruptedAfterReturn.get()).isTrue();
        assertThat(lookupInterrupted.get(3, TimeUnit.SECONDS)).isTrue();
        releaseLookup.countDown();
    }

    @Test
    @DisplayName("on failure, the page keeps earlier extenders' contributions and gains no partial total_cost")
    void failurePreservesEarlierExtenderContributions() {
        final Map<String, Object> row1 = row(RUN_ID.toString(), TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, 0.9);
        final Map<String, Object> row2 =
                row(RUN_ID_2.toString(), TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, 0.5);
        final QueryResultPage page = new QueryResultPage(List.of(row1, row2), 2L);
        when(batchRunTotalCostLookup.fetchTotalCosts(any(), any())).thenThrow(new IllegalStateException("boom"));

        final QueryResultPage result = extender(batchRunTotalCostLookup, new SimpleAsyncTaskExecutor(), 2)
                .extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        assertThat(result.rows().get(0))
                .containsEntry(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, 0.9)
                .doesNotContainKey(TestSuiteRunQueryFields.TOTAL_COST_FIELD);
        assertThat(result.rows().get(1))
                .containsEntry(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, 0.5)
                .doesNotContainKey(TestSuiteRunQueryFields.TOTAL_COST_FIELD);
    }

    @Test
    @DisplayName("this extender never throws on failure, so a coordinator's later extender still applies")
    void neverThrowsSoLaterExtenderStillApplies() {
        when(batchRunTotalCostLookup.fetchTotalCosts(any(), any())).thenThrow(new IllegalStateException("boom"));
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        final QueryResultPageExtender laterExtender = (query, extended) -> {
            final Map<String, Object> withLaterKey =
                    new LinkedHashMap<>(extended.rows().get(0));
            withLaterKey.put("later_key", "later_value");
            return new QueryResultPage(List.of(withLaterKey), extended.totalCount());
        };

        final QueryResultPage afterFirst = extender(batchRunTotalCostLookup, new SimpleAsyncTaskExecutor(), 2)
                .extend(rowQuery(), page);
        final QueryResultPage afterSecond = laterExtender.extend(rowQuery(), afterFirst);

        assertThat(afterFirst).isSameAs(page);
        assertThat(afterSecond.rows().get(0)).containsEntry("later_key", "later_value");
    }
}
