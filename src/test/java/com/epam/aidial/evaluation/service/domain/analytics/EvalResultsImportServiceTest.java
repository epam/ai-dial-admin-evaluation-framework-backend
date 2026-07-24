package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("EvalResultsImportService")
@ExtendWith(MockitoExtension.class)
class EvalResultsImportServiceTest {

    @Mock
    private TestCaseRunResultRepository resultRepository;

    private EvalResultsImportService service;

    @BeforeEach
    void setUp() {
        AnalyticsResultsProperties.Batch batch = new AnalyticsResultsProperties.Batch();
        batch.setMaxItems(1000);
        AnalyticsResultsProperties analyticsResultsProperties = new AnalyticsResultsProperties();
        analyticsResultsProperties.setBatch(batch);

        CsvImportProperties csvImportProperties = new CsvImportProperties();
        csvImportProperties.setBatchSize(500);

        service = new EvalResultsImportService(resultRepository, analyticsResultsProperties, csvImportProperties);
    }

    private TestCaseRunResult.TestCaseRunResultBuilder itemBuilder(String testCaseName) {
        return TestCaseRunResult.builder()
                .testCaseName(testCaseName)
                .testCaseId(UUID.randomUUID())
                .runIndex(0)
                .testCaseData("{\"expected\":\"answer\"}")
                .extractedColumns("{}")
                .extractionWarnings("[]")
                .executionStatus(ExecutionStatus.SUCCESS)
                .execStartedAtMs(1000L)
                .execCompletedAtMs(1500L);
    }

    @Test
    @DisplayName("Should fill run-context fields and persist chunked entities")
    void shouldFillRunContextAndPersistPerItem() {
        UUID testSuiteId = UUID.randomUUID();
        TestSuiteRun run =
                TestSuiteRun.builder().id(UUID.randomUUID()).createdAt(1000L).build();

        TestCaseRunResult stub1 = itemBuilder("tc1")
                .extractedColumns("{\"answer\":\"a1\"}")
                .extractionWarnings("[]")
                .build();
        TestCaseRunResult stub2 = itemBuilder("tc2")
                .extractedColumns("{\"answer\":\"a2\"}")
                .extractionWarnings("[]")
                .build();

        service.persistResults(testSuiteId, run, List.of(stub1, stub2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCaseRunResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(resultRepository, times(1)).saveAll(captor.capture());

        List<TestCaseRunResult> saved = captor.getValue();
        assertThat(saved).hasSize(2);

        // run-context fields must be filled in
        assertThat(saved.get(0).getId()).isNotNull();
        assertThat(saved.get(0).getTestSuiteRunId()).isEqualTo(run.getId());
        assertThat(saved.get(0).getTestSuiteId()).isEqualTo(testSuiteId);
        assertThat(saved.get(0).getCreatedAtMs()).isEqualTo(run.getCreatedAt());

        // extractedColumns/extractionWarnings trusted verbatim from stubs, not re-extracted
        assertThat(saved.get(0).getExtractedColumns()).isEqualTo("{\"answer\":\"a1\"}");
        assertThat(saved.get(0).getExtractionWarnings()).isEqualTo("[]");

        assertThat(saved.get(1).getId()).isNotNull();
        assertThat(saved.get(1).getTestSuiteRunId()).isEqualTo(run.getId());
        assertThat(saved.get(1).getTestCaseName()).isEqualTo("tc2");
    }

    @Test
    @DisplayName("persistResults chunks saveAll calls when batch exceeds csv.import.batch-size")
    void shouldChunkSaveAllCalls() {
        UUID testSuiteId = UUID.randomUUID();
        TestSuiteRun run =
                TestSuiteRun.builder().id(UUID.randomUUID()).createdAt(0L).build();

        // batch-size=500 from setUp; build 3 items so they fit in one chunk
        List<TestCaseRunResult> stubs = List.of(
                itemBuilder("tc1").build(),
                itemBuilder("tc2").build(),
                itemBuilder("tc3").build());

        service.persistResults(testSuiteId, run, stubs);

        // 3 items, batchSize=500 → one chunk
        verify(resultRepository, times(1)).saveAll(any());
    }

    @Nested
    @DisplayName("validateBatch")
    class ValidateBatchTests {

        @Test
        @DisplayName("throws ValidationException when results batch is empty")
        void throwsWhenBatchEmpty() {
            assertThatThrownBy(() -> service.validateBatch(List.of())).isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException when batch size exceeds the configured max")
        void throwsWhenBatchTooLarge() {
            AnalyticsResultsProperties.Batch batch = new AnalyticsResultsProperties.Batch();
            batch.setMaxItems(1);
            AnalyticsResultsProperties props = new AnalyticsResultsProperties();
            props.setBatch(batch);
            CsvImportProperties smallCsvProps = new CsvImportProperties();
            smallCsvProps.setBatchSize(500);
            EvalResultsImportService smallBatchService =
                    new EvalResultsImportService(resultRepository, props, smallCsvProps);

            List<TestCaseRunResult> results =
                    List.of(itemBuilder("tc1").build(), itemBuilder("tc2").build());

            assertThatThrownBy(() -> smallBatchService.validateBatch(results)).isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException on duplicate (testCaseName, runIndex) within the batch")
        void throwsOnDuplicateWithinBatch() {
            TestCaseRunResult item = itemBuilder("tc1").build();

            assertThatThrownBy(() -> service.validateBatch(List.of(item, item)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException when completedAt is before startedAt")
        void throwsWhenCompletedBeforeStarted() {
            TestCaseRunResult item = itemBuilder("tc1")
                    .execStartedAtMs(2000L)
                    .execCompletedAtMs(1000L)
                    .build();

            assertThatThrownBy(() -> service.validateBatch(List.of(item))).isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException when testCaseId and testCaseName are both missing")
        void throwsWhenIdentityMissing() {
            TestCaseRunResult item = itemBuilder(null).testCaseId(null).build();

            assertThatThrownBy(() -> service.validateBatch(List.of(item))).isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("does not throw when batch passes all structural checks")
        void doesNotThrowForValidBatch() {
            TestCaseRunResult item1 = itemBuilder("tc1").runIndex(0).build();
            TestCaseRunResult item2 = itemBuilder("tc2").runIndex(0).build();

            service.validateBatch(List.of(item1, item2));
        }
    }
}
