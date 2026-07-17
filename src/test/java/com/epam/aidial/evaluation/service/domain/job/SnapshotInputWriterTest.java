package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.service.domain.mapper.DisabledTestCaseIdsCodec;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SnapshotInputWriter")
@ExtendWith(MockitoExtension.class)
class SnapshotInputWriterTest {

    // Mirrors the private SnapshotInputWriter.SNAPSHOT_PAGE_SIZE; the paging test depends on this value.
    private static final int PAGE_SIZE = 100;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RunnableTestCaseSelector selector;

    @Mock
    private TestCaseRunInputRepository inputRepository;

    @Captor
    private ArgumentCaptor<List<TestCaseRunInput>> batchCaptor;

    @Captor
    private ArgumentCaptor<Collection<UUID>> excludedIdsCaptor;

    private SnapshotInputWriter writer;

    @BeforeEach
    void setUp() {
        // Real collaborators (per the test contract): only the selector + input repository are mocked.
        final ConversationAssembler assembler = new ConversationAssembler(objectMapper);
        final DisabledTestCaseIdsCodec codec = new DisabledTestCaseIdsCodec(objectMapper);
        writer = new SnapshotInputWriter(selector, assembler, inputRepository, codec);
    }

    @Test
    @DisplayName("clears leftover inputs and writes nothing for an empty dataset, returning 0")
    void emptyDatasetClearsAndWritesNothing() {
        final int written = writer.writeInputs(RUN_ID, DATASET_ID, null, null);

        assertThat(written).isZero();
        verify(inputRepository).deleteByRunId(RUN_ID);
        verify(inputRepository, never()).insertBatch(any());
    }

    @Test
    @DisplayName("writes single-turn test cases as length-1 units with sequential positions")
    void writesSingleTurnUnits() {
        final TestCase a = singleTurn("a", "{\"x\":1}");
        final TestCase b = singleTurn("b", "{\"x\":2}");
        when(selector.loadRunnableSingleTurnPage(eq(DATASET_ID), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(a, b));

        final int written = writer.writeInputs(RUN_ID, DATASET_ID, null, null);

        assertThat(written).isEqualTo(2);
        verify(inputRepository).insertBatch(batchCaptor.capture());
        final List<TestCaseRunInput> inputs = batchCaptor.getValue();
        assertThat(inputs).extracting(TestCaseRunInput::getPosition).containsExactly(0, 1);
        assertThat(inputs).extracting(TestCaseRunInput::getTestCaseId).containsExactly(a.getId(), b.getId());
        assertThat(inputs).allSatisfy(i -> {
            assertThat(i.getRunId()).isEqualTo(RUN_ID);
            assertThat(i.getConversationId()).isNull();
            assertThat(i.getTurns()).isNull();
            assertThat(i.isBroken()).isFalse();
        });
    }

    @Test
    @DisplayName("assembles a contiguous conversation into one runnable unit carrying its frozen turns")
    void writesRunnableConversationUnit() {
        final UUID conversationId = UUID.randomUUID();
        final TestCase t0 = turn(conversationId, 0, true);
        final TestCase t1 = turn(conversationId, 1, true);
        stubConversation(conversationId, List.of(t0, t1));

        final int written = writer.writeInputs(RUN_ID, DATASET_ID, null, null);

        assertThat(written).isEqualTo(1);
        verify(inputRepository).insertBatch(batchCaptor.capture());
        final TestCaseRunInput input = batchCaptor.getValue().getFirst();
        assertThat(input.getPosition()).isZero();
        assertThat(input.getConversationId()).isEqualTo(conversationId);
        assertThat(input.getTotalTurns()).isEqualTo(2);
        assertThat(input.isBroken()).isFalse();
        assertThat(input.getTestCaseId()).isEqualTo(t0.getId());
        assertThat(turnCount(input.getTurns())).isEqualTo(2);
    }

    @Test
    @DisplayName("marks a non-contiguous conversation broken (0/0, no turns) but still writes one unit")
    void marksNonContiguousConversationBroken() {
        final UUID conversationId = UUID.randomUUID();
        final TestCase t0 = turn(conversationId, 0, true);
        final TestCase t2 = turn(conversationId, 2, true); // gap at index 1
        stubConversation(conversationId, List.of(t0, t2));

        final int written = writer.writeInputs(RUN_ID, DATASET_ID, null, null);

        assertThat(written).isEqualTo(1);
        verify(inputRepository).insertBatch(batchCaptor.capture());
        final TestCaseRunInput input = batchCaptor.getValue().getFirst();
        assertThat(input.isBroken()).isTrue();
        assertThat(input.getTotalTurns()).isZero();
        assertThat(input.getTurns()).isNull();
    }

    @Test
    @DisplayName("continues positions from single-turn units into conversation units across both phases")
    void positionsContinueAcrossPhases() {
        final TestCase single = singleTurn("solo", "{}");
        when(selector.loadRunnableSingleTurnPage(eq(DATASET_ID), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(single));
        final UUID conversationId = UUID.randomUUID();
        stubConversation(conversationId, List.of(turn(conversationId, 0, true)));

        final int written = writer.writeInputs(RUN_ID, DATASET_ID, null, null);

        assertThat(written).isEqualTo(2);
        verify(inputRepository, times(2)).insertBatch(batchCaptor.capture());
        final List<List<TestCaseRunInput>> batches = batchCaptor.getAllValues();
        assertThat(batches.get(0).getFirst().getPosition()).isZero();
        assertThat(batches.get(0).getFirst().getConversationId()).isNull();
        assertThat(batches.get(1).getFirst().getPosition()).isEqualTo(1);
        assertThat(batches.get(1).getFirst().getConversationId()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("parses disabledTestCaseIds via the real codec and excludes the disabled tail turn from assembly")
    void excludesDisabledTailTurnParsedFromJson() {
        final UUID conversationId = UUID.randomUUID();
        final TestCase t0 = turn(conversationId, 0, true);
        final TestCase t1 = turn(conversationId, 1, true);
        final TestCase t2 = turn(conversationId, 2, true);
        stubConversation(conversationId, List.of(t0, t1, t2));
        final String disabledJson = "[\"" + t2.getId() + "\"]";

        final int written = writer.writeInputs(RUN_ID, DATASET_ID, null, disabledJson);

        assertThat(written).isEqualTo(1);
        // The disabled tail turn is excluded → survivors 0,1 form a contiguous prefix → runnable, 2 turns.
        verify(inputRepository).insertBatch(batchCaptor.capture());
        final TestCaseRunInput input = batchCaptor.getValue().getFirst();
        assertThat(input.isBroken()).isFalse();
        assertThat(input.getTotalTurns()).isEqualTo(2);
        assertThat(turnCount(input.getTurns())).isEqualTo(2);
        // The same parsed id set is passed to the single-turn selection as the exclusion.
        verify(selector)
                .loadRunnableSingleTurnPage(eq(DATASET_ID), any(), excludedIdsCaptor.capture(), anyInt(), anyInt());
        assertThat(excludedIdsCaptor.getValue()).containsExactly(t2.getId());
    }

    @Test
    @DisplayName("skips a fully-disabled conversation entirely (no unit, not a broken row)")
    void skipsFullyDisabledConversation() {
        final UUID conversationId = UUID.randomUUID();
        final TestCase t0 = turn(conversationId, 0, true);
        final TestCase t2 = turn(conversationId, 2, true);
        stubConversation(conversationId, List.of(t0, t2));
        final String disabledJson = "[\"" + t0.getId() + "\",\"" + t2.getId() + "\"]";

        final int written = writer.writeInputs(RUN_ID, DATASET_ID, null, disabledJson);

        assertThat(written).isZero();
        verify(inputRepository).deleteByRunId(RUN_ID);
        verify(inputRepository, never()).insertBatch(any());
    }

    @Test
    @DisplayName("pages single-turn selection until a short page and numbers positions across pages")
    void pagesSingleTurnUntilShortPage() {
        final List<TestCase> fullPage = IntStream.range(0, PAGE_SIZE)
                .mapToObj(i -> singleTurn("tc-" + i, "{}"))
                .toList();
        final List<TestCase> tailPage = List.of(singleTurn("tail", "{}"));
        when(selector.loadRunnableSingleTurnPage(eq(DATASET_ID), any(), any(), eq(0), anyInt()))
                .thenReturn(fullPage);
        when(selector.loadRunnableSingleTurnPage(eq(DATASET_ID), any(), any(), eq(PAGE_SIZE), anyInt()))
                .thenReturn(tailPage);

        final int written = writer.writeInputs(RUN_ID, DATASET_ID, null, null);

        assertThat(written).isEqualTo(PAGE_SIZE + 1);
        verify(inputRepository, times(2)).insertBatch(batchCaptor.capture());
        final List<Integer> positions = batchCaptor.getAllValues().stream()
                .flatMap(List::stream)
                .map(TestCaseRunInput::getPosition)
                .toList();
        assertThat(positions)
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(0, PAGE_SIZE).boxed().toList());
    }

    private void stubConversation(UUID conversationId, List<TestCase> turns) {
        when(selector.loadRunnableConversationIdsPage(eq(DATASET_ID), any(), anyInt(), anyInt()))
                .thenReturn(List.of(conversationId.toString()));
        when(selector.loadConversationTurns(eq(DATASET_ID), any(), any())).thenReturn(turns);
    }

    private TestCase singleTurn(String name, String data) {
        return TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(DATASET_ID)
                .testCaseName(name)
                .data(data)
                .valid(true)
                .build();
    }

    private TestCase turn(UUID conversationId, int index, boolean valid) {
        return TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(DATASET_ID)
                .testCaseName("turn-" + index)
                .data("{\"q\":" + index + "}")
                .conversationId(conversationId)
                .turnIndex(index)
                .valid(valid)
                .build();
    }

    private int turnCount(String turnsJson) {
        final JsonNode node = objectMapper.readTree(turnsJson);
        return node.size();
    }
}
