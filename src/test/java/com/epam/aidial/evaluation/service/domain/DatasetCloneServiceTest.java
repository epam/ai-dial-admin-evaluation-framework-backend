package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.validation.RevalidationProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("DatasetCloneService Unit Tests")
@ExtendWith(MockitoExtension.class)
class DatasetCloneServiceTest {

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private FileService fileService;

    @Mock
    private RevalidationProperties revalidationProperties;

    @Captor
    private ArgumentCaptor<Dataset> datasetCaptor;

    @Captor
    private ArgumentCaptor<List<TestCase>> testCasesCaptor;

    private DatasetCloneService datasetCloneService;

    @BeforeEach
    void setUp() {
        datasetCloneService =
                new DatasetCloneService(datasetRepository, testCaseRepository, fileService, revalidationProperties);
    }

    @Test
    @DisplayName("deriveCloneName returns '<name> (clone)' when no collision exists")
    void deriveCloneNameUsesBaseSuffixWhenNoCollision() {
        when(datasetRepository.existsByNameIgnoreCase("My Data (clone)")).thenReturn(false);

        assertThat(datasetCloneService.deriveCloneName("My Data")).isEqualTo("My Data (clone)");
    }

    @Test
    @DisplayName("deriveCloneName appends an incrementing counter to skip existing clone names")
    void deriveCloneNameDedupesWithCounter() {
        when(datasetRepository.existsByNameIgnoreCase("My Data (clone)")).thenReturn(true);
        when(datasetRepository.existsByNameIgnoreCase("My Data (clone 2)")).thenReturn(true);
        when(datasetRepository.existsByNameIgnoreCase("My Data (clone 3)")).thenReturn(false);

        assertThat(datasetCloneService.deriveCloneName("My Data")).isEqualTo("My Data (clone 3)");
    }

    @Test
    @DisplayName("cloneRowAndTestCases inserts the cloned dataset as PRIVATE, copying schema and validation verbatim")
    void cloneRowAndTestCasesInsertsPrivateDatasetCopyingState() {
        Dataset source = sourceDataset();
        when(revalidationProperties.getBatchSize()).thenReturn(50);
        when(datasetRepository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(testCaseRepository.findBatchByDatasetId(eq(source.getId()), eq(0), eq(50)))
                .thenReturn(List.of());

        UUID newDatasetId = UUID.randomUUID();
        datasetCloneService.cloneRowAndTestCases(source, newDatasetId, "cloner@example.com", 123L);

        verify(datasetRepository).createWithId(datasetCaptor.capture(), eq(123L));
        Dataset inserted = datasetCaptor.getValue();
        assertThat(inserted.getId()).isEqualTo(newDatasetId);
        assertThat(inserted.getName()).isEqualTo("Src (clone)");
        assertThat(inserted.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
        assertThat(inserted.getTestCaseSchema()).isEqualTo(source.getTestCaseSchema());
        assertThat(inserted.isValid()).isEqualTo(source.isValid());
        assertThat(inserted.getValidationWarnings()).isEqualTo(source.getValidationWarnings());
        assertThat(inserted.getCreatedBy()).isEqualTo("cloner@example.com");
    }

    @Test
    @DisplayName(
            "cloneRowAndTestCases copies test cases with new ids, repointed datasetId, and @ef/datasets ref rewrite")
    void cloneRowAndTestCasesRemapsIdsAndRewritesRefs() {
        Dataset source = sourceDataset();
        UUID oldTcId = UUID.randomUUID();
        TestCase sourceCase = TestCase.builder()
                .id(oldTcId)
                .datasetId(source.getId())
                .testCaseName("case-1")
                .data("{\"file\":\"@ef/datasets/" + source.getId() + "/data.csv\"}")
                .valid(true)
                .validationWarnings("[]")
                .build();

        when(revalidationProperties.getBatchSize()).thenReturn(50);
        lenient().when(datasetRepository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(testCaseRepository.findBatchByDatasetId(eq(source.getId()), eq(0), eq(50)))
                .thenReturn(List.of(sourceCase));
        when(testCaseRepository.findBatchByDatasetId(eq(source.getId()), eq(1), eq(50)))
                .thenReturn(List.of());

        UUID newDatasetId = UUID.randomUUID();
        Map<UUID, UUID> idMap =
                datasetCloneService.cloneRowAndTestCases(source, newDatasetId, "cloner@example.com", 123L);

        verify(testCaseRepository).batchInsert(testCasesCaptor.capture(), eq(123L));
        List<TestCase> inserted = testCasesCaptor.getValue();
        assertThat(inserted).hasSize(1);
        TestCase clonedCase = inserted.get(0);

        // New id, recorded in the returned map
        assertThat(clonedCase.getId()).isNotEqualTo(oldTcId);
        assertThat(idMap).containsEntry(oldTcId, clonedCase.getId());
        // Repointed to the new dataset
        assertThat(clonedCase.getDatasetId()).isEqualTo(newDatasetId);
        assertThat(clonedCase.getTestCaseName()).isEqualTo("case-1");
        // @ef/datasets ref rewritten from source to new dataset id
        assertThat(clonedCase.getData()).isEqualTo("{\"file\":\"@ef/datasets/" + newDatasetId + "/data.csv\"}");
    }

    private static Dataset sourceDataset() {
        return Dataset.builder()
                .id(UUID.randomUUID())
                .name("Src")
                .description("source desc")
                .testCaseSchema("[{\"name\":\"query\",\"type\":\"STRING\"}]")
                .valid(true)
                .validationWarnings("[]")
                .visibility(DatasetVisibility.PRIVATE)
                .version(0L)
                .createdBy("orig@example.com")
                .build();
    }
}
