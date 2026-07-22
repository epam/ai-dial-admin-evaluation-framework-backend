package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteRunProperties;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.service.domain.analytics.EvalResultsImportService;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalResultsImportItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalResultsImportRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.job.ExecutionSettingsValidator;
import com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteRunMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteRunService.importResultsAndEvaluate")
@ExtendWith(MockitoExtension.class)
class TestSuiteRunServiceTest {

    @Mock
    private TestSuiteRunRepository testSuiteRunRepository;

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private RunnableTestCaseCounter runnableTestCaseCounter;

    @Mock
    private TestSuiteEvaluationJob evaluationJob;

    @Mock
    private ExecutionSettingsValidator executionSettingsValidator;

    @Mock
    private TestSuiteRunSseService sseService;

    @Mock
    private TestSuiteRunMapper mapper;

    @Mock
    private FilterParser filterParser;

    @Mock
    private SortParser sortParser;

    @Mock
    private EvalResultsImportService evalResultsImportService;

    private TestSuiteRunService service;
    private UUID testSuiteId;
    private UUID datasetId;

    @BeforeEach
    void setUp() {
        TestSuiteRunProperties.Limits limits = new TestSuiteRunProperties.Limits();
        limits.setMaxConcurrentRunsGlobal(100);
        limits.setMaxConcurrentRunsPerSuite(100);
        TestSuiteRunProperties properties = new TestSuiteRunProperties();
        properties.setLimits(limits);

        service = new TestSuiteRunService(
                testSuiteRunRepository,
                testSuiteRepository,
                runnableTestCaseCounter,
                properties,
                evaluationJob,
                executionSettingsValidator,
                sseService,
                mapper,
                filterParser,
                sortParser,
                new ObjectMapper(),
                evalResultsImportService);

        testSuiteId = UUID.randomUUID();
        datasetId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private TestSuite validBoundSuite() {
        return TestSuite.builder()
                .id(testSuiteId)
                .datasetId(datasetId)
                .valid(true)
                .responseColumns("[]")
                .build();
    }

    private EvalResultsImportRequestDto requestWithOneItem(String testCaseName) {
        EvalResultsImportItemDto item = EvalResultsImportItemDto.builder()
                .testCaseName(testCaseName)
                .runIndex(0)
                .build();
        return EvalResultsImportRequestDto.builder().results(List.of(item)).build();
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("throws EntityNotFoundException when suite does not exist")
        void throwsWhenSuiteNotFound() {
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.importResultsAndEvaluate(testSuiteId, requestWithOneItem("tc1")))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("throws DatasetVisibilityRuleException when suite has no dataset")
        void throwsWhenSuiteUnbound() {
            TestSuite suite = TestSuite.builder()
                    .id(testSuiteId)
                    .datasetId(null)
                    .valid(true)
                    .build();
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.of(suite));

            assertThatThrownBy(() -> service.importResultsAndEvaluate(testSuiteId, requestWithOneItem("tc1")))
                    .isInstanceOf(DatasetVisibilityRuleException.class);
        }

        @Test
        @DisplayName("throws InvalidOperationException when suite is not valid")
        void throwsWhenSuiteInvalid() {
            TestSuite suite = TestSuite.builder()
                    .id(testSuiteId)
                    .datasetId(datasetId)
                    .valid(false)
                    .build();
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.of(suite));

            assertThatThrownBy(() -> service.importResultsAndEvaluate(testSuiteId, requestWithOneItem("tc1")))
                    .isInstanceOf(InvalidOperationException.class);
        }
    }

    @Nested
    @DisplayName("afterCommit dispatch")
    class AfterCommitDispatch {

        @BeforeEach
        void setUpResolvableSuite() {
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.of(validBoundSuite()));
            when(testSuiteRunRepository.nextRunNameSequenceValue()).thenReturn(1L);
            when(testSuiteRunRepository.save(any(TestSuiteRun.class))).thenAnswer(invocation -> {
                TestSuiteRun run = invocation.getArgument(0);
                run.setId(UUID.randomUUID());
                return run;
            });
            when(mapper.toDto(any(TestSuiteRun.class)))
                    .thenReturn(TestSuiteRunResponseDto.builder().build());
        }

        private void invokeAndFireAfterCommit(EvalResultsImportRequestDto request) {
            TransactionSynchronizationManager.initSynchronization();
            service.importResultsAndEvaluate(testSuiteId, request);
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        }

        @Test
        @DisplayName(
                "dispatches Phase 2+3 evaluation when result persistence succeeds, passing items through unresolved")
        void dispatchesEvaluationOnSuccess() {
            invokeAndFireAfterCommit(requestWithOneItem("tc1"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<EvalResultsImportItemDto>> itemsCaptor = ArgumentCaptor.forClass(List.class);
            verify(evalResultsImportService)
                    .persistResults(eq(testSuiteId), any(TestSuiteRun.class), itemsCaptor.capture(), any());
            assertThat(itemsCaptor.getValue()).hasSize(1);
            assertThat(itemsCaptor.getValue().get(0).getTestCaseName()).isEqualTo("tc1");
            assertThat(itemsCaptor.getValue().get(0).getTestCaseId()).isNull();

            verify(evaluationJob).registerCancellationSignal(any(UUID.class));
            verify(evaluationJob).executeRunAsync(any(UUID.class), isNull(), eq(true));
            verify(testSuiteRunRepository, never()).updateToFailed(any(), any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("marks the run FAILED and never dispatches evaluation when result persistence fails")
        void marksRunFailedWhenPersistenceFails() {
            doThrow(new RuntimeException("analytics write failed"))
                    .when(evalResultsImportService)
                    .persistResults(eq(testSuiteId), any(TestSuiteRun.class), any(), any());
            when(testSuiteRunRepository.findById(any(UUID.class)))
                    .thenReturn(Optional.of(
                            TestSuiteRun.builder().id(UUID.randomUUID()).build()));

            invokeAndFireAfterCommit(requestWithOneItem("tc1"));

            verify(testSuiteRunRepository)
                    .updateToFailed(
                            any(UUID.class),
                            eq("Failed to persist imported eval results"),
                            any(),
                            anyLong(),
                            anyLong());
            verify(sseService).notifyStatusUpdate(any(TestSuiteRun.class));
            verify(evaluationJob, never()).executeRunAsync(any(), any(), anyBoolean());
            verify(evaluationJob, never()).registerCancellationSignal(any());
        }
    }
}
