package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.service.domain.mapper.DisabledTestCaseIdsCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writes the frozen execution-unit input rows ({@code test_case_run_inputs}) for a suite run during the
 * snapshot phase. This is the single owner of the snapshot's row selection, multiTurn assembly, and
 * batched persistence; {@link TestSuiteEvaluationJob} calls {@link #writeInputs} inside its snapshot
 * transaction and uses the returned count as the authoritative {@code number_of_test_cases}.
 *
 * <p>Two execution-unit kinds are written, in deterministic order:
 *
 * <ol>
 *   <li>runnable SINGLE-TURN test cases ({@code multi_turn_id IS NULL}) as length-1 units;
 *   <li>MULTI_TURNS with at least one filter-matching turn as one assembled unit each (paged by distinct
 *       {@code multi_turn_id} so a multiTurn is never split across a page), with
 *       {@link MultiTurnAssembler} resolving runnable-vs-broken in memory.
 * </ol>
 *
 * <p>Only the suite's {@code testCaseFilter} is applied in SQL (row-level, like disable); validity and the
 * {@code disabledTestCaseIds} exclusion are resolved during assembly. Writing is idempotent — any leftover
 * inputs from a prior failed attempt are cleared first — so a snapshot retry is safe.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class SnapshotInputWriter {

    private static final int SNAPSHOT_PAGE_SIZE = 100;

    private final RunnableTestCaseSelector runnableTestCaseSelector;
    private final MultiTurnAssembler multiTurnAssembler;
    private final TestCaseRunInputRepository testCaseRunInputRepository;
    private final DisabledTestCaseIdsCodec disabledTestCaseIdsCodec;

    /**
     * Clears any leftover inputs for {@code runId}, then writes all execution-unit input rows for the suite.
     * Must be called inside the caller's snapshot transaction.
     *
     * @param disabledTestCaseIdsJson the suite's {@code disabledTestCaseIds} JSONB payload (array of UUID strings)
     * @return the total number of execution units written (single-turn units + multiTurn units)
     */
    public int writeInputs(UUID runId, UUID datasetId, String filterJson, String disabledTestCaseIdsJson) {
        final List<UUID> disabledIds = disabledTestCaseIdsCodec.deserialize(disabledTestCaseIdsJson);
        final Set<UUID> excludedSet = new HashSet<>(disabledIds);

        testCaseRunInputRepository.deleteByRunId(runId);

        int position = writeSingleTurnUnits(runId, datasetId, filterJson, disabledIds, 0);
        position = writeMultiTurnUnits(runId, datasetId, filterJson, excludedSet, position);
        return position;
    }

    /**
     * Writes the runnable SINGLE-TURN test cases as length-1 execution units, in deterministic order.
     * Returns the next free {@code position}.
     */
    private int writeSingleTurnUnits(
            UUID runId, UUID datasetId, String filterJson, List<UUID> disabledIds, int startPosition) {
        int position = startPosition;
        int offset = 0;
        List<TestCase> page;
        do {
            page = runnableTestCaseSelector.loadRunnableSingleTurnPage(
                    datasetId, filterJson, disabledIds, offset, SNAPSHOT_PAGE_SIZE);
            if (!page.isEmpty()) {
                final List<TestCaseRunInput> batch = new ArrayList<>(page.size());
                for (TestCase tc : page) {
                    batch.add(TestCaseRunInput.builder()
                            .runId(runId)
                            .position(position++)
                            .testCaseId(tc.getId())
                            .testCaseName(tc.getTestCaseName())
                            .testCaseData(tc.getData())
                            .build());
                }
                testCaseRunInputRepository.insertBatch(batch);
            }
            offset += SNAPSHOT_PAGE_SIZE;
        } while (page.size() >= SNAPSHOT_PAGE_SIZE);
        return position;
    }

    /**
     * Writes MULTI_TURNS with at least one filter-matching turn as assembled per-multiTurn execution units
     * (one input row each), paging by distinct {@code multi_turn_id} so a multiTurn is never split across a
     * page. Only the filter is applied in SQL (row-level, like disable); each multiTurn's filter-matching turns
     * are grouped and handed to {@link MultiTurnAssembler}, which resolves runnable-vs-broken (validity,
     * tail-only disable, contiguity, cap) in memory. Returns the next free {@code position}.
     */
    private int writeMultiTurnUnits(
            UUID runId, UUID datasetId, String filterJson, Set<UUID> excludedSet, int startPosition) {
        int position = startPosition;
        int offset = 0;
        List<String> multiTurnIds;
        do {
            multiTurnIds = runnableTestCaseSelector.loadRunnableMultiTurnIdsPage(
                    datasetId, filterJson, offset, SNAPSHOT_PAGE_SIZE);
            if (!multiTurnIds.isEmpty()) {
                final List<TestCase> allTurns =
                        runnableTestCaseSelector.loadMultiTurnTurns(datasetId, multiTurnIds, filterJson);
                final Map<UUID, List<TestCase>> turnsByMultiTurn = allTurns.stream()
                        .collect(Collectors.groupingBy(
                                TestCase::getMultiTurnId, LinkedHashMap::new, Collectors.toList()));

                final List<TestCaseRunInput> batch = new ArrayList<>(multiTurnIds.size());
                for (String multiTurnId : multiTurnIds) {
                    final List<TestCase> turns = turnsByMultiTurn.get(UUID.fromString(multiTurnId));
                    if (turns == null || turns.isEmpty()) {
                        continue;
                    }
                    // A fully-disabled multiTurn yields no unit (empty) — it drops out of the run entirely.
                    final Optional<MultiTurnAssembler.AssembledMultiTurn> assembledOpt =
                            multiTurnAssembler.assemble(turns, excludedSet);
                    if (assembledOpt.isEmpty()) {
                        continue;
                    }
                    final MultiTurnAssembler.AssembledMultiTurn assembled = assembledOpt.get();
                    batch.add(TestCaseRunInput.builder()
                            .runId(runId)
                            .position(position++)
                            .testCaseId(assembled.representativeTestCaseId())
                            .testCaseName(assembled.representativeTestCaseName())
                            .testCaseData(assembled.representativeTestCaseData())
                            .multiTurnId(assembled.multiTurnId())
                            .totalTurns(assembled.totalTurns())
                            .turns(assembled.turnsJson())
                            .broken(assembled.broken())
                            .build());
                }
                if (!batch.isEmpty()) {
                    testCaseRunInputRepository.insertBatch(batch);
                }
            }
            offset += SNAPSHOT_PAGE_SIZE;
        } while (multiTurnIds.size() >= SNAPSHOT_PAGE_SIZE);
        return position;
    }
}
