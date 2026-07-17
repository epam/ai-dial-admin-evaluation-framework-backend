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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writes the frozen execution-unit input rows ({@code test_case_run_inputs}) for a suite run during the
 * snapshot phase. This is the single owner of the snapshot's row selection, conversation assembly, and
 * batched persistence; {@link TestSuiteEvaluationJob} calls {@link #writeInputs} inside its snapshot
 * transaction and uses the returned count as the authoritative {@code number_of_test_cases}.
 *
 * <p>Two execution-unit kinds are written, in deterministic order:
 *
 * <ol>
 *   <li>runnable SINGLE-TURN test cases ({@code conversation_id IS NULL}) as length-1 units;
 *   <li>CONVERSATIONS with at least one filter-matching turn as one assembled unit each (paged by distinct
 *       {@code conversation_id} so a conversation is never split across a page), with
 *       {@link ConversationAssembler} resolving runnable-vs-broken in memory.
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
    private final ConversationAssembler conversationAssembler;
    private final TestCaseRunInputRepository testCaseRunInputRepository;
    private final DisabledTestCaseIdsCodec disabledTestCaseIdsCodec;

    /**
     * Clears any leftover inputs for {@code runId}, then writes all execution-unit input rows for the suite.
     * Must be called inside the caller's snapshot transaction.
     *
     * @param disabledTestCaseIdsJson the suite's {@code disabledTestCaseIds} JSONB payload (array of UUID strings)
     * @return the total number of execution units written (single-turn units + conversation units)
     */
    public int writeInputs(UUID runId, UUID datasetId, String filterJson, String disabledTestCaseIdsJson) {
        final List<UUID> disabledIds = disabledTestCaseIdsCodec.deserialize(disabledTestCaseIdsJson);
        final Set<UUID> excludedSet = new HashSet<>(disabledIds);

        testCaseRunInputRepository.deleteByRunId(runId);

        int position = writeSingleTurnUnits(runId, datasetId, filterJson, disabledIds, 0);
        position = writeConversationUnits(runId, datasetId, filterJson, excludedSet, position);
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
     * Writes CONVERSATIONS with at least one filter-matching turn as assembled per-conversation execution units
     * (one input row each), paging by distinct {@code conversation_id} so a conversation is never split across a
     * page. Only the filter is applied in SQL (row-level, like disable); each conversation's filter-matching turns
     * are grouped and handed to {@link ConversationAssembler}, which resolves runnable-vs-broken (validity,
     * tail-only disable, contiguity, cap) in memory. Returns the next free {@code position}.
     */
    private int writeConversationUnits(
            UUID runId, UUID datasetId, String filterJson, Set<UUID> excludedSet, int startPosition) {
        int position = startPosition;
        int offset = 0;
        List<String> conversationIds;
        do {
            conversationIds = runnableTestCaseSelector.loadRunnableConversationIdsPage(
                    datasetId, filterJson, offset, SNAPSHOT_PAGE_SIZE);
            if (!conversationIds.isEmpty()) {
                final List<TestCase> allTurns =
                        runnableTestCaseSelector.loadConversationTurns(datasetId, conversationIds, filterJson);
                final Map<UUID, List<TestCase>> turnsByConversation = allTurns.stream()
                        .collect(Collectors.groupingBy(
                                TestCase::getConversationId, LinkedHashMap::new, Collectors.toList()));

                final List<TestCaseRunInput> batch = new ArrayList<>(conversationIds.size());
                for (String conversationId : conversationIds) {
                    final List<TestCase> turns = turnsByConversation.get(UUID.fromString(conversationId));
                    if (turns == null || turns.isEmpty()) {
                        continue;
                    }
                    final ConversationAssembler.AssembledConversation assembled =
                            conversationAssembler.assemble(turns, excludedSet);
                    batch.add(TestCaseRunInput.builder()
                            .runId(runId)
                            .position(position++)
                            .testCaseId(assembled.representativeTestCaseId())
                            .testCaseName(assembled.representativeTestCaseName())
                            .testCaseData(assembled.representativeTestCaseData())
                            .conversationId(assembled.conversationId())
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
        } while (conversationIds.size() >= SNAPSHOT_PAGE_SIZE);
        return position;
    }
}
