package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.job.PerTurnBindingDetector;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.util.TestCaseTurnsCsvSerializer;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
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

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public ResolvedRequestDto resolveRequest(UUID testSuiteId, UUID testCaseId) {
        TestSuite suite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));
        // Test cases now live on the dataset; resolve via the suite's datasetId so the suite-scoped
        // route still returns the test case the caller meant, while the underlying lookup is dataset-rooted.
        TestCase tc = testCaseRepository
                .findByIdAndDatasetId(testCaseId, suite.getDatasetId())
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + testCaseId));

        // Template + bindings are suite-owned now (overrides have been dropped per the dataset refactor).
        RequestTemplateDto template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
        List<InputBindingDto> bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());

        Map<String, Object> data = warningsSerializer.deserializeMap(tc.getData());

        return requestResolver.resolve(template, bindings, data);
    }

    /**
     * Plans the turn sequence for try-it-out's multi-turn execution, mirroring {@code TurnLoopExecutor
     * .buildTurnPlan}'s turn-count decision so a preview never disagrees with what a real run would do for
     * the same suite/test case: a single-turn case (no {@code multiTurnData}) plans exactly one turn from
     * its shared {@code data}; a multi-turn case with no effective binding on a {@code perTurn=true} schema
     * field collapses to one turn built from shared {@code data} only; otherwise one turn per {@code
     * multiTurnData} entry, each merged with the shared data (per-turn wins on key collision).
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public TurnPlan planTurns(UUID testSuiteId, UUID testCaseId) {
        TestSuite suite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));
        TestCase tc = testCaseRepository
                .findByIdAndDatasetId(testCaseId, suite.getDatasetId())
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + testCaseId));

        RequestTemplateDto template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
        List<InputBindingDto> bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());
        List<ResponseColumnDefinitionDto> responseColumns = jsonbMapper.mapResponseColumns(suite.getResponseColumns());

        Map<String, Object> sharedData = warningsSerializer.deserializeMap(tc.getData());
        List<Map<String, Object>> turns = turnsSerializer.deserializeTurns(tc.getMultiTurnData());

        List<Map<String, Object>> turnDataList;
        if (turns == null || turns.isEmpty()) {
            turnDataList = List.of(sharedData);
        } else {
            List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(suite.getDatasetId());
            boolean perTurn = perTurnBindingDetector.referencesPerTurnField(bindings, schema);
            turnDataList = perTurn
                    ? turns.stream()
                            .map(turn -> mergeSharedAndTurn(sharedData, turn))
                            .toList()
                    : List.of(sharedData);
        }

        return new TurnPlan(template, bindings, responseColumns, turnDataList);
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

    /** The planned turn sequence for a try-it-out invocation: one turn's effective data per list entry. */
    public record TurnPlan(
            RequestTemplateDto template,
            List<InputBindingDto> bindings,
            List<ResponseColumnDefinitionDto> responseColumns,
            List<Map<String, Object>> turnDataList) {}
}
