package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.service.translate.FilterTranslator;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.job.RunnableTestCaseSelector;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Experimental-layer implementation of the stable {@link RunnableTestCaseSelector} interface
 * (interface inversion, mirroring {@code MetricScoreComputation}). Translates a suite's stored
 * {@code testCaseFilter} into a jOOQ {@link Condition} via the Structured Query DSL translation layer
 * ({@link FilterTranslator} + {@link TestCaseFieldBindingsBuilder}), then delegates the actual paged
 * SELECT / count to {@link TestCaseRepository} so all {@code test_cases} SQL stays in the data layer
 * and runs inside the caller's ambient transaction. A {@code null}/blank filter short-circuits to the
 * validity + exclusion predicate (behavior identical to the pre-filter snapshot).
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class QueryDslRunnableTestCaseSelector implements RunnableTestCaseSelector {

    private static final String ENTITY = "test_cases";

    private final TestCaseRepository testCaseRepository;
    private final TestCaseFieldBindingsBuilder bindingsBuilder;
    private final FilterTranslator filterTranslator;
    private final ObjectMapper objectMapper;

    @Override
    public long countRunnable(UUID datasetId, String filterJson, Collection<UUID> excludedIds) {
        final Condition filter = compile(datasetId, filterJson);
        return filter == null
                ? testCaseRepository.countValidByDatasetIdExcludingIds(datasetId, excludedIds)
                : testCaseRepository.countValidByDatasetIdExcludingIdsMatching(datasetId, excludedIds, filter);
    }

    @Override
    public List<TestCase> loadRunnablePage(
            UUID datasetId, String filterJson, Collection<UUID> excludedIds, int offset, int limit) {
        final Condition filter = compile(datasetId, filterJson);
        return filter == null
                ? testCaseRepository.findValidByDatasetIdExcludingIds(datasetId, excludedIds, offset, limit)
                : testCaseRepository.findValidByDatasetIdExcludingIdsMatching(
                        datasetId, excludedIds, filter, offset, limit);
    }

    @Override
    public void validateFilter(UUID datasetId, String filterJson) {
        // Successful translation (against the dataset's typed bindings) is the validity check.
        compile(datasetId, filterJson);
    }

    /**
     * Parses and translates the stored filter into an ALL-turns-match jOOQ {@link Condition}; {@code null}
     * when there is no filter (unfiltered selection is byte-identical to today). The filter is compiled once
     * with scope-aware bindings: per-turn fields resolve against the per-turn element {@code elem}, shared
     * fields against the outer row's {@code data} (constant across turns). It is wrapped as a universal
     * quantifier over {@code coalesce(multi_turn_data, jsonb_build_array(data))}: a case is runnable iff no
     * turn fails the filter. {@code IS NOT TRUE} makes a turn whose predicate is false <i>or unknown</i>
     * (e.g. a missing per-turn field) count as failing. Shared-field references remain correlated to the
     * outer row inside the lateral. A single-turn case is the trivial one-element array, preserving current
     * behavior.
     */
    private Condition compile(UUID datasetId, String filterJson) {
        final FilterNode filter = parseFilter(filterJson);
        if (filter == null) {
            return null;
        }
        final Field<JSONB> elem = DSL.field(DSL.name("t", "elem"), JSONB.class);
        final Map<String, QueryFieldBinding> bindings = bindingsBuilder.buildScoped(datasetId, elem);
        final Condition perTurn = filterTranslator.toCondition(filter, bindings);

        final Table<?> turns = DSL.table(
                        "jsonb_array_elements(coalesce({0}, jsonb_build_array({1})))",
                        TEST_CASES.MULTI_TURN_DATA, TEST_CASES.DATA)
                .as("t", "elem");
        return DSL.notExists(DSL.selectOne().from(turns).where(DSL.condition("({0}) is not true", perTurn)));
    }

    /**
     * Parses a stored {@code testCaseFilter} (a bare filter subtree) into a {@link FilterNode} by
     * wrapping it in a minimal {@code test_cases} query envelope so the wired
     * {@code FilterNodeDeserializer} handles the routing. Returns {@code null} for a null/blank filter.
     */
    private FilterNode parseFilter(String filterJson) {
        if (filterJson == null || filterJson.isBlank()) {
            return null;
        }
        try {
            final ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("entity", ENTITY);
            envelope.put("mode", "row");
            envelope.set("filter", objectMapper.readTree(filterJson));
            final StructuredQuery query = objectMapper.treeToValue(envelope, StructuredQuery.class);
            return query.filter();
        } catch (JacksonException e) {
            log.warn("Rejecting malformed test-case filter: {}", e.getMessage(), e);
            throw new ValidationException("The test-case filter is not valid JSON: " + e.getMessage());
        }
    }
}
