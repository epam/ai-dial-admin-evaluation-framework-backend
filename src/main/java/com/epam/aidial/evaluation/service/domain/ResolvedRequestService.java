package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.job.PerTurnBindingDetector;
import com.epam.aidial.evaluation.runner.job.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.util.TestCaseTurnsCsvSerializer;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Try-It-Out preview API: resolves a suite's persisted template + bindings against a test case's data.
 * Delegates the actual resolution to the shared module's {@link RequestResolver} (see Decision 3 in the
 * {@code evaluation-runner-core-module} change's {@code design.md}) — this class owns only the DB-backed
 * lookup of the suite/test-case entities.
 */
@Service
@LogExecution
@RequiredArgsConstructor
public class ResolvedRequestService {

    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseRepository testCaseRepository;
    private final JsonbMapper jsonbMapper;
    private final ValidationWarningsSerializer warningsSerializer;
    private final TestCaseTurnsCsvSerializer turnsSerializer;
    private final RequestResolver requestResolver;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final PerTurnBindingDetector perTurnBindingDetector;

    /**
     * Delegates to {@link #resolveRequest(UUID, UUID, int)} previewing request #0 — the suite's own
     * request — so the pre-existing {@code TryItOutService} call site (which never needs to preview a
     * chained request) stays untouched.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public ResolvedRequestDto resolveRequest(UUID testSuiteId, UUID testCaseId) {
        return resolveRequest(testSuiteId, testCaseId, 0);
    }

    /**
     * Previews the resolved request for one request in the suite's chain: {@code requestIndex == 0}
     * selects the suite's own {@code requestTemplate}/{@code inputBindings}; {@code requestIndex > 0}
     * selects {@code additionalRequests[requestIndex - 1]}. Out of range (negative, or greater than
     * {@code additionalRequests.size()}) is rejected with a {@link ValidationException} (HTTP 400 per
     * design D19). Resolution always uses an <strong>empty</strong> JSONata frame — no chain is executed
     * to populate prior requests' response columns, so a chained request's references to earlier
     * columns resolve as JSONata undefined and surface as validation warnings, exactly like any other
     * unresolved placeholder.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public ResolvedRequestDto resolveRequest(UUID testSuiteId, UUID testCaseId, int requestIndex) {
        final TestSuite suite = loadSuite(testSuiteId);
        // Test cases now live on the dataset; resolve via the suite's datasetId so the suite-scoped
        // route still returns the test case the caller meant, while the underlying lookup is dataset-rooted.
        final TestCase tc = testCaseRepository
                .findByIdAndDatasetId(testCaseId, suite.getDatasetId())
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + testCaseId));

        RequestTemplateDto template;
        List<InputBindingDto> bindings;
        if (requestIndex == 0) {
            // Template + bindings are suite-owned now (overrides have been dropped per the dataset refactor).
            template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
            bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());
        } else {
            RequestDefinitionDto additionalRequest = resolveAdditionalRequest(suite, requestIndex);
            template = additionalRequest.getRequestTemplate();
            bindings = additionalRequest.getInputBindings();
        }

        Map<String, Object> data = warningsSerializer.deserializeMap(tc.getData());

        return requestResolver.resolve(template, bindings, data);
    }

    private RequestDefinitionDto resolveAdditionalRequest(TestSuite suite, int requestIndex) {
        List<RequestDefinitionDto> additionalRequests =
                jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests());
        int additionalIndex = requestIndex - 1;
        if (requestIndex < 0 || additionalRequests == null || additionalIndex >= additionalRequests.size()) {
            throw new ValidationException("requestIndex " + requestIndex
                    + " is out of range for suite " + suite.getId() + " (chain length "
                    + (additionalRequests == null ? 0 : additionalRequests.size() + 1) + ")");
        }
        return additionalRequests.get(additionalIndex);
    }

    /**
     * Plans the whole request chain for a test-case try-it-out, mirroring {@code RequestChainExecutor
     * .buildSpecs} (spec construction) and {@code TurnLoopExecutor.buildTurnPlan} (per-request turn-count
     * decision) so a preview never disagrees with what a real run would do for the same suite/test case.
     * Spec 0 is built from the suite's own fields (labelled by the suite-level {@code requestName}); specs
     * 1..N from {@code additionalRequests} in order. Turn count is decided <strong>per request</strong>
     * from that request's own {@code inputBindings}: a single-turn case (no readable {@code multiTurnData}
     * turns) plans exactly one turn from shared {@code data} for every request; otherwise each request
     * either collapses to one shared-data turn (no effective binding on a {@code perTurn=true} schema
     * field) or plans one merged turn per {@code multiTurnData} entry. The dataset schema is loaded
     * lazily, at most once for the whole chain — only when the case has readable turns.
     *
     * @param additionalRequests the suite's already-deserialized {@code additionalRequests} (nullable /
     *     empty for a single-request suite). The caller maps them for its own precondition pass before the
     *     test case is looked up (400-before-404, design D7) and passes them in here, so one try-out never
     *     deserializes that JSONB column twice.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public ChainPlan planChain(UUID testSuiteId, UUID testCaseId, List<RequestDefinitionDto> additionalRequests) {
        final TestSuite suite = loadSuite(testSuiteId);
        final TestCase tc = testCaseRepository
                .findByIdAndDatasetId(testCaseId, suite.getDatasetId())
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + testCaseId));

        final List<RequestExecutionSpec> specs = buildSpecs(suite, additionalRequests);

        final Map<String, Object> sharedData = warningsSerializer.deserializeMap(tc.getData());
        final List<Map<String, Object>> turns = turnsSerializer.deserializeTurns(tc.getMultiTurnData());

        if (turns == null || turns.isEmpty()) {
            return singleTurnChainPlan(specs, sharedData);
        }

        // Lazy schema read: reached only when multiTurnData yields readable turns, once for the whole chain.
        final List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(suite.getDatasetId());
        final List<Map<String, Object>> mergedTurns =
                turns.stream().map(turn -> mergeSharedAndTurn(sharedData, turn)).toList();
        final List<Map<String, Object>> collapsedTurn = List.of(sharedData);
        final List<RequestPlan> requestPlans = specs.stream()
                .map(spec -> new RequestPlan(
                        spec,
                        perTurnBindingDetector.referencesPerTurnField(spec.inputBindings(), schema)
                                ? mergedTurns
                                : collapsedTurn))
                .toList();
        return new ChainPlan(requestPlans);
    }

    /**
     * Plans the request chain for variables-mode try-it-out: suite fields + {@code additionalRequests}
     * only — no test case is loaded (variables mode has none), so there is no turn planning and every
     * request plans exactly one turn over an empty data map. {@code additionalRequests} is supplied
     * already-deserialized by the caller for the same reason as in {@link #planChain}.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public ChainPlan planChainForVariables(UUID testSuiteId, List<RequestDefinitionDto> additionalRequests) {
        return singleTurnChainPlan(buildSpecs(loadSuite(testSuiteId), additionalRequests), Map.of());
    }

    private TestSuite loadSuite(UUID testSuiteId) {
        return testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));
    }

    /** One single-turn {@link RequestPlan} per spec, every request sharing the same one-turn data list. */
    private static ChainPlan singleTurnChainPlan(List<RequestExecutionSpec> specs, Map<String, Object> data) {
        final List<Map<String, Object>> singleTurn = List.of(data);
        return new ChainPlan(
                specs.stream().map(spec -> new RequestPlan(spec, singleTurn)).toList());
    }

    /**
     * Builds the ordered chain of {@link RequestExecutionSpec}s from the suite entity, mirroring {@code
     * RequestChainExecutor.buildSpecs}: spec 0 from the suite's own fields with the suite-level {@code
     * requestName} threaded into the spec's {@code name} component, specs 1..N from the caller-supplied
     * {@code additionalRequests} with null bindings/columns normalized to empty lists.
     */
    private List<RequestExecutionSpec> buildSpecs(TestSuite suite, List<RequestDefinitionDto> additionalRequests) {
        final int additionalCount = additionalRequests != null ? additionalRequests.size() : 0;
        final int totalRequests = 1 + additionalCount;

        final List<RequestExecutionSpec> specs = new ArrayList<>(totalRequests);
        specs.add(new RequestExecutionSpec(
                0,
                totalRequests,
                suite.getRequestName(),
                jsonbMapper.mapEndpointContract(suite.getEndpointRef()),
                jsonbMapper.mapRequestTemplate(suite.getRequestTemplate()),
                jsonbMapper.mapInputBindings(suite.getInputBindings()),
                jsonbMapper.mapResponseColumns(suite.getResponseColumns())));

        for (int i = 0; i < additionalCount; i++) {
            // Invariant: additionalRequests never contains a null element here — TestSuiteRequestValidator
            // rejects null chain elements with a hard 400 at write time, so persisted JSONB is clean.
            final RequestDefinitionDto definition = additionalRequests.get(i);
            specs.add(new RequestExecutionSpec(
                    i + 1,
                    totalRequests,
                    definition.getName(),
                    definition.getEndpointRef(),
                    definition.getRequestTemplate(),
                    definition.getInputBindings() != null ? definition.getInputBindings() : List.of(),
                    definition.getResponseColumns() != null ? definition.getResponseColumns() : List.of()));
        }
        return specs;
    }

    /** Merges the case's shared data with one turn's own map; per-turn keys win on collision. */
    private static Map<String, Object> mergeSharedAndTurn(Map<String, Object> shared, Map<String, Object> turn) {
        if (shared.isEmpty()) {
            return turn;
        }
        Map<String, Object> merged = new LinkedHashMap<>(shared);
        merged.putAll(turn);
        return merged;
    }

    /**
     * One request's planned execution within a try-it-out chain: the request's {@link
     * RequestExecutionSpec} (its position, label, endpoint, template, bindings and response columns)
     * paired with the turn sequence planned for it — one effective data map per turn, decided from this
     * request's own bindings (any subset of the chain may be multi-turn).
     */
    public record RequestPlan(RequestExecutionSpec spec, List<Map<String, Object>> turnDataList) {}

    /**
     * The planned request chain for a try-it-out invocation, in request-major order: {@code
     * requestPlans.get(0)} is the suite's own request (#0), followed by the {@code additionalRequests}
     * entries in chain order. Execution iterates requests in this order and, within each request, its
     * {@link RequestPlan#turnDataList()} turns in order (turn-minor).
     */
    public record ChainPlan(List<RequestPlan> requestPlans) {

        /** Total planned invocations across the chain: the sum of every request's turn count. */
        public int totalInvocations() {
            return requestPlans.stream()
                    .mapToInt(plan -> plan.turnDataList().size())
                    .sum();
        }
    }
}
