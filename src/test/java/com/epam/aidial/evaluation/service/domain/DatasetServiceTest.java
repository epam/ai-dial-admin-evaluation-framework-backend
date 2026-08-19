package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.DatasetCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityErrorCode;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationException;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.DatasetMapper;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DatasetService")
@ExtendWith(MockitoExtension.class)
class DatasetServiceTest {

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private DatasetCascadeService datasetCascadeService;

    @Mock
    private DatasetCloneService datasetCloneService;

    @Mock
    private TestSuiteService testSuiteService;

    @Mock
    private TestCaseService testCaseService;

    @Mock
    private AuthorResolver authorResolver;

    @Mock
    private RevalidationService revalidationService;

    @Mock
    private SchemaValidationService schemaValidationService;

    @Mock
    private FileService fileService;

    @Mock
    private PlatformTransactionManager metaTransactionManager;

    @Mock
    private SortParser sortParser;

    @Mock
    private FilterParser filterParser;

    @Mock
    private TransactionTimestampContext transactionTimestampContext;

    private DatasetService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonbMapper jsonbMapper = new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper));
        ValidationWarningsSerializer warningsSerializer = new ValidationWarningsSerializer(objectMapper);
        DatasetMapper datasetMapper = new DatasetMapper(jsonbMapper, warningsSerializer);
        service = new DatasetService(
                datasetRepository,
                datasetCascadeService,
                datasetCloneService,
                testSuiteService,
                testCaseService,
                datasetMapper,
                jsonbMapper,
                authorResolver,
                revalidationService,
                schemaValidationService,
                fileService,
                metaTransactionManager,
                sortParser,
                filterParser,
                objectMapper,
                transactionTimestampContext);
    }

    // -----------------------------------------------------------------------
    // create
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "create rethrows DataIntegrityViolation with SQLState 23505 as UniqueConstraintViolationException carrying the duplicated name")
    void createTranslatesUniqueViolationToTypedException() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("dup")
                .visibility(DatasetVisibility.PUBLIC)
                .build();
        when(authorResolver.getCreatedBy(any())).thenReturn("alice");
        SQLException sqlEx = new SQLException("dup", "23505");
        DataIntegrityViolationException div = new DataIntegrityViolationException("dup", sqlEx);
        when(datasetRepository.save(any())).thenThrow(div);

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(UniqueConstraintViolationException.class)
                .hasMessageContaining("dup");
    }

    // -----------------------------------------------------------------------
    // update — schema diff detection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("update with unchanged schema returns null revalidationTask and does not invalidate cache")
    void updateUnchangedSchemaDoesNotStartRevalidation() {
        UUID id = UUID.randomUUID();
        String existingSchema =
                "[{\"name\":\"q\",\"type\":\"STRING\",\"required\":true,\"displayName\":null,\"description\":null}]";
        Dataset existing = Dataset.builder()
                .id(id)
                .name("D")
                .testCaseSchema(existingSchema)
                .validationWarnings("[]")
                .version(1L)
                .build();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("D")
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("q")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()))
                .build();

        DatasetUpdateResultDto result = service.update(id, request, 1L);

        assertThat(result.getRevalidationTask()).isNull();
        verify(schemaValidationService, never()).invalidateSchemaCache(any());
        verify(revalidationService, never()).startDatasetRevalidation(any());
        verify(testCaseService, never()).removeDataFields(any(), anyList());
    }

    @Test
    @DisplayName("update with schema change invalidates cache, starts revalidation, and returns the task in the result")
    void updateSchemaChangeStartsRevalidation() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder()
                .id(id)
                .name("D")
                .testCaseSchema("[{\"name\":\"old\",\"type\":\"STRING\",\"required\":false}]")
                .validationWarnings("[]")
                .version(2L)
                .build();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RevalidationTaskDto task =
                RevalidationTaskDto.builder().taskId(UUID.randomUUID()).build();
        when(revalidationService.startDatasetRevalidation(id)).thenReturn(task);

        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("D")
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("new")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        DatasetUpdateResultDto result = service.update(id, request, 2L);

        assertThat(result.getRevalidationTask()).isSameAs(task);
        verify(schemaValidationService).invalidateSchemaCache(id);
        verify(revalidationService).startDatasetRevalidation(id);
        verify(testCaseService).removeDataFields(eq(id), eq(List.of("old")));
    }

    @Test
    @DisplayName("update treats reordered schema keys as no-op (semantic JSON equality)")
    void updateReorderedKeysIsNotSchemaChange() {
        UUID id = UUID.randomUUID();
        // Same fields, keys in different order — Jackson semantic equality should treat as unchanged
        Dataset existing = Dataset.builder()
                .id(id)
                .name("D")
                .testCaseSchema("[{\"required\":true,\"description\":null,\"displayName\":null,"
                        + "\"type\":\"STRING\",\"name\":\"q\"}]")
                .validationWarnings("[]")
                .version(1L)
                .build();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("D")
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("q")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()))
                .build();

        DatasetUpdateResultDto result = service.update(id, request, 1L);

        assertThat(result.getRevalidationTask()).isNull();
        verify(revalidationService, never()).startDatasetRevalidation(any());
    }

    // -----------------------------------------------------------------------
    // update — error paths
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("update with mismatched expectedVersion throws VersionConflictException without writing")
    void updateVersionMismatchThrows() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder().id(id).name("D").version(5L).build();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(existing));

        DatasetRequestDto request = DatasetRequestDto.builder().name("D").build();

        assertThatThrownBy(() -> service.update(id, request, 4L))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("expected 4")
                .hasMessageContaining("current is 5");

        verify(datasetRepository, never()).save(any());
        verify(revalidationService, never()).startDatasetRevalidation(any());
    }

    @Test
    @DisplayName("update on missing id throws EntityNotFoundException")
    void updateMissingDatasetThrows() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        service.update(id, DatasetRequestDto.builder().name("X").build(), null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("update rethrows save-time DataIntegrityViolation 23505 as UniqueConstraintViolationException")
    void updateTranslatesUniqueViolationToTypedException() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder()
                .id(id)
                .name("Old")
                .version(1L)
                .validationWarnings("[]")
                .build();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(existing));
        SQLException sqlEx = new SQLException("dup", "23505");
        DataIntegrityViolationException div = new DataIntegrityViolationException("dup", sqlEx);
        when(datasetRepository.save(any())).thenThrow(div);

        DatasetRequestDto request = DatasetRequestDto.builder().name("New").build();

        assertThatThrownBy(() -> service.update(id, request, null))
                .isInstanceOf(UniqueConstraintViolationException.class)
                .hasMessageContaining("New");
    }

    // -----------------------------------------------------------------------
    // getById
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getById on missing id throws EntityNotFoundException")
    void getByIdMissingThrows() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("getById returns mapped DTO when dataset exists")
    void getByIdReturnsDto() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder()
                .id(id)
                .name("My Dataset")
                .version(1L)
                .validationWarnings("[]")
                .build();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(existing));

        DatasetResponseDto dto = service.getById(id);

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getName()).isEqualTo("My Dataset");
    }

    // -----------------------------------------------------------------------
    // delete — RESTRICT FK behavior
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "delete with referencing suites throws InvalidOperationException listing suite names (HTTP 409) for PUBLIC datasets")
    void deleteRejectsWhenReferencingSuitesExist() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        when(datasetRepository.findById(id))
                .thenReturn(Optional.of(Dataset.builder()
                        .id(id)
                        .visibility(DatasetVisibility.PUBLIC)
                        .validationWarnings("[]")
                        .build()));
        when(testSuiteService.getReferencingDataset(id))
                .thenReturn(List.of(
                        TestSuiteResponseDto.builder()
                                .id(UUID.randomUUID())
                                .name("suiteA")
                                .build(),
                        TestSuiteResponseDto.builder()
                                .id(UUID.randomUUID())
                                .name("suiteB")
                                .build()));

        assertThatThrownBy(() -> service.delete(id, false))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("suiteA")
                .hasMessageContaining("suiteB")
                .hasMessageContaining("2 test suite");

        verify(datasetCascadeService, never()).deleteById(any());
        verify(schemaValidationService, never()).invalidateSchemaCache(any());
    }

    @Test
    @DisplayName("delete with no referencing suites deletes the dataset and invalidates schema cache (PUBLIC)")
    void deleteSucceedsWhenNoReferencingSuites() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        when(datasetRepository.findById(id))
                .thenReturn(Optional.of(Dataset.builder()
                        .id(id)
                        .visibility(DatasetVisibility.PUBLIC)
                        .validationWarnings("[]")
                        .build()));
        when(testSuiteService.getReferencingDataset(id)).thenReturn(List.of());

        service.delete(id, false);

        verify(datasetCascadeService).deleteById(id);
        verify(schemaValidationService).invalidateSchemaCache(id);
    }

    @Test
    @DisplayName("delete on missing id throws EntityNotFoundException")
    void deleteMissingThrows() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        when(datasetRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, false))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(id.toString());

        verify(datasetCascadeService, never()).deleteById(any());
    }

    @Test
    @DisplayName(
            "delete translates post-check FK race (DataIntegrityViolation) into InvalidOperationException with current dependent suites (PUBLIC)")
    void deleteHandlesRaceConditionAsInvalidOperation() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        when(datasetRepository.findById(id))
                .thenReturn(Optional.of(Dataset.builder()
                        .id(id)
                        .visibility(DatasetVisibility.PUBLIC)
                        .validationWarnings("[]")
                        .build()));
        when(testSuiteService.getReferencingDataset(id))
                .thenReturn(List.of())
                .thenReturn(List.of(TestSuiteResponseDto.builder()
                        .id(UUID.randomUUID())
                        .name("raceSuite")
                        .build()));
        doThrow(new DataIntegrityViolationException("FK violation"))
                .when(datasetCascadeService)
                .deleteById(id);

        assertThatThrownBy(() -> service.delete(id, false))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("raceSuite");

        verify(schemaValidationService, never()).invalidateSchemaCache(any());
    }

    @Test
    @DisplayName(
            "delete on PRIVATE dataset unbinds suites and deletes without checking referencing suites or raising 409")
    void deletePrivateCascadesUnbindAndDelete() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        when(datasetRepository.findById(id))
                .thenReturn(Optional.of(Dataset.builder()
                        .id(id)
                        .visibility(DatasetVisibility.PRIVATE)
                        .validationWarnings("[]")
                        .build()));

        service.delete(id, false);

        verify(testSuiteService).unbindAllFromDataset(id);
        verify(datasetCascadeService).deleteById(id);
        verify(testSuiteService, never()).getReferencingDataset(any());
        verify(schemaValidationService).invalidateSchemaCache(id);
    }

    // -----------------------------------------------------------------------
    // create — visibility + bindToSuiteId
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("create PUBLIC without bindToSuiteId persists the dataset and does not touch any suite")
    void createPublicWithoutBindingSucceeds() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Public Dataset")
                .visibility(DatasetVisibility.PUBLIC)
                .build();
        when(authorResolver.getCreatedBy(any())).thenReturn("alice");
        when(datasetRepository.save(any())).thenAnswer(inv -> {
            Dataset arg = inv.getArgument(0);
            arg.setId(UUID.randomUUID());
            arg.setValidationWarnings("[]");
            arg.setVisibility(DatasetVisibility.PUBLIC);
            return arg;
        });

        DatasetResponseDto dto = service.create(request, null);

        assertThat(dto.getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
        verify(testSuiteService, never()).bindDataset(any(), any());
    }

    @Test
    @DisplayName(
            "create PUBLIC with bindToSuiteId is rejected before any persistence (PUBLIC_DATASET_FORBIDS_SUITE_BINDING / HTTP 400)")
    void createPublicWithBindingRejected() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Public Dataset")
                .visibility(DatasetVisibility.PUBLIC)
                .bindToSuiteId(UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(DatasetVisibilityRuleException.class)
                .satisfies(ex -> assertThat(((DatasetVisibilityRuleException) ex).getErrorCode())
                        .isEqualTo(DatasetVisibilityErrorCode.PUBLIC_DATASET_FORBIDS_SUITE_BINDING));

        verify(datasetRepository, never()).save(any());
        verify(testSuiteService, never()).bindDataset(any(), any());
    }

    @Test
    @DisplayName(
            "create PRIVATE without bindToSuiteId is rejected before any persistence (PRIVATE_DATASET_REQUIRES_SUITE_BINDING / HTTP 400)")
    void createPrivateWithoutBindingRejected() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Private Dataset")
                .visibility(DatasetVisibility.PRIVATE)
                .build();

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(DatasetVisibilityRuleException.class)
                .satisfies(ex -> assertThat(((DatasetVisibilityRuleException) ex).getErrorCode())
                        .isEqualTo(DatasetVisibilityErrorCode.PRIVATE_DATASET_REQUIRES_SUITE_BINDING));

        verify(datasetRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "create PRIVATE with valid bindToSuiteId atomically inserts dataset and delegates suite binding to TestSuiteService")
    void createPrivateAtomicallyBindsTargetSuite() {
        UUID suiteId = UUID.randomUUID();
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Private Dataset")
                .visibility(DatasetVisibility.PRIVATE)
                .bindToSuiteId(suiteId)
                .build();
        when(authorResolver.getCreatedBy(any())).thenReturn("bob");
        UUID newDatasetId = UUID.randomUUID();
        when(datasetRepository.save(any())).thenAnswer(inv -> {
            Dataset arg = inv.getArgument(0);
            arg.setId(newDatasetId);
            arg.setValidationWarnings("[]");
            arg.setVisibility(DatasetVisibility.PRIVATE);
            return arg;
        });

        DatasetResponseDto dto = service.create(request, null);

        assertThat(dto.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
        verify(testSuiteService).bindDataset(suiteId, newDatasetId);
    }

    @Test
    @DisplayName("create PRIVATE with bindToSuiteId pointing at a non-existent suite throws EntityNotFoundException")
    void createPrivateWithMissingSuiteThrows() {
        UUID suiteId = UUID.randomUUID();
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Private Dataset")
                .visibility(DatasetVisibility.PRIVATE)
                .bindToSuiteId(suiteId)
                .build();
        when(authorResolver.getCreatedBy(any())).thenReturn("bob");
        when(datasetRepository.save(any())).thenAnswer(inv -> {
            Dataset arg = inv.getArgument(0);
            arg.setId(UUID.randomUUID());
            arg.setValidationWarnings("[]");
            arg.setVisibility(DatasetVisibility.PRIVATE);
            return arg;
        });
        when(testSuiteService.bindDataset(eq(suiteId), any()))
                .thenThrow(new EntityNotFoundException("TestSuite not found with id: " + suiteId));

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(suiteId.toString());
    }

    // -----------------------------------------------------------------------
    // transitionVisibility
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "transitionVisibility PUBLIC→PRIVATE with 0 bindings throws PRIVATE_TRANSITION_INVALID_BINDING_COUNT (HTTP 409)")
    void transitionPublicToPrivateZeroBindingsRejected() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder()
                .id(id)
                .name("D")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .build();
        when(datasetRepository.findByIdForUpdate(id)).thenReturn(Optional.of(existing));
        when(testSuiteService.countReferencingDataset(id)).thenReturn(0L);

        assertThatThrownBy(() -> service.transitionVisibility(id, DatasetVisibility.PRIVATE))
                .isInstanceOf(DatasetVisibilityRuleException.class)
                .satisfies(ex -> assertThat(((DatasetVisibilityRuleException) ex).getErrorCode())
                        .isEqualTo(DatasetVisibilityErrorCode.PRIVATE_TRANSITION_INVALID_BINDING_COUNT));

        verify(datasetRepository, never()).updateVisibility(any(), any(), eq(0L));
    }

    @Test
    @DisplayName("transitionVisibility PUBLIC→PRIVATE with exactly 1 binding succeeds and persists the new visibility")
    void transitionPublicToPrivateOneBindingSucceeds() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder()
                .id(id)
                .name("D")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .build();
        Dataset refreshed = Dataset.builder()
                .id(id)
                .name("D")
                .visibility(DatasetVisibility.PRIVATE)
                .validationWarnings("[]")
                .version(2L)
                .build();
        when(datasetRepository.findByIdForUpdate(id)).thenReturn(Optional.of(existing));
        when(testSuiteService.countReferencingDataset(id)).thenReturn(1L);
        when(transactionTimestampContext.getTimestamp()).thenReturn(1_700_000_000_000L);
        when(datasetRepository.findById(id)).thenReturn(Optional.of(refreshed));

        DatasetResponseDto dto = service.transitionVisibility(id, DatasetVisibility.PRIVATE);

        assertThat(dto.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
        verify(datasetRepository).updateVisibility(id, DatasetVisibility.PRIVATE, 1_700_000_000_000L);
    }

    @Test
    @DisplayName("transitionVisibility PUBLIC→PRIVATE with 2+ bindings throws PRIVATE_TRANSITION_INVALID_BINDING_COUNT")
    void transitionPublicToPrivateMultipleBindingsRejected() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder()
                .id(id)
                .name("D")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .build();
        when(datasetRepository.findByIdForUpdate(id)).thenReturn(Optional.of(existing));
        when(testSuiteService.countReferencingDataset(id)).thenReturn(3L);

        assertThatThrownBy(() -> service.transitionVisibility(id, DatasetVisibility.PRIVATE))
                .isInstanceOf(DatasetVisibilityRuleException.class)
                .hasMessageContaining("current: 3");
    }

    @Test
    @DisplayName("transitionVisibility PRIVATE→PUBLIC always succeeds and skips the binding-count check")
    void transitionPrivateToPublicAlwaysSucceeds() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder()
                .id(id)
                .name("D")
                .visibility(DatasetVisibility.PRIVATE)
                .validationWarnings("[]")
                .build();
        Dataset refreshed = Dataset.builder()
                .id(id)
                .name("D")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .version(2L)
                .build();
        when(datasetRepository.findByIdForUpdate(id)).thenReturn(Optional.of(existing));
        when(transactionTimestampContext.getTimestamp()).thenReturn(1L);
        when(datasetRepository.findById(id)).thenReturn(Optional.of(refreshed));

        DatasetResponseDto dto = service.transitionVisibility(id, DatasetVisibility.PUBLIC);

        assertThat(dto.getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
        verify(testSuiteService, never()).countReferencingDataset(any());
        verify(datasetRepository).updateVisibility(id, DatasetVisibility.PUBLIC, 1L);
    }

    @Test
    @DisplayName("transitionVisibility no-op (target equals current) returns the unchanged dataset without writes")
    void transitionNoOpReturnsUnchanged() {
        UUID id = UUID.randomUUID();
        Dataset existing = Dataset.builder()
                .id(id)
                .name("D")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .version(7L)
                .build();
        when(datasetRepository.findByIdForUpdate(id)).thenReturn(Optional.of(existing));

        DatasetResponseDto dto = service.transitionVisibility(id, DatasetVisibility.PUBLIC);

        assertThat(dto.getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
        assertThat(dto.getVersion()).isEqualTo(7L);
        verify(datasetRepository, never()).updateVisibility(any(), any(), eq(0L));
        verify(testSuiteService, never()).countReferencingDataset(any());
    }

    @Test
    @DisplayName("transitionVisibility on missing dataset throws EntityNotFoundException")
    void transitionMissingDatasetThrows() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transitionVisibility(id, DatasetVisibility.PRIVATE))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    // -----------------------------------------------------------------------
    // clone
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "clone without name/description derives the name, copies the source description, and inherits source visibility")
    void cloneDerivesNameAndInheritsVisibility() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        Dataset source = Dataset.builder()
                .id(id)
                .name("Src")
                .description("src-desc")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .build();
        stubCloneReads(id, source, DatasetVisibility.PUBLIC);
        when(authorResolver.getCreatedBy(any())).thenReturn("alice");
        when(datasetCloneService.deriveCloneName("Src")).thenReturn("Src (clone)");
        when(transactionTimestampContext.getTimestamp()).thenReturn(1_700_000_000_000L);

        DatasetResponseDto dto = service.clone(id, new DatasetCloneRequestDto(), null);

        assertThat(dto.getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
        verify(datasetCloneService)
                .cloneRowAndTestCases(
                        eq(source),
                        any(UUID.class),
                        eq("Src (clone)"),
                        eq("src-desc"),
                        eq("alice"),
                        eq(1_700_000_000_000L),
                        eq(DatasetVisibility.PUBLIC));
    }

    @Test
    @DisplayName("clone with explicit name and description uses them verbatim and does not derive a name")
    void cloneUsesExplicitNameAndDescription() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        Dataset source = Dataset.builder()
                .id(id)
                .name("Src")
                .description("src-desc")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .build();
        stubCloneReads(id, source, DatasetVisibility.PUBLIC);
        when(authorResolver.getCreatedBy(any())).thenReturn("bob");
        when(transactionTimestampContext.getTimestamp()).thenReturn(42L);

        DatasetCloneRequestDto request = DatasetCloneRequestDto.builder()
                .name("Custom")
                .description("Overridden")
                .build();
        service.clone(id, request, null);

        verify(datasetCloneService, never()).deriveCloneName(any());
        verify(datasetCloneService)
                .cloneRowAndTestCases(
                        eq(source),
                        any(UUID.class),
                        eq("Custom"),
                        eq("Overridden"),
                        eq("bob"),
                        eq(42L),
                        eq(DatasetVisibility.PUBLIC));
    }

    @Test
    @DisplayName("clone copies DIAL files before the transactional row/test-case write")
    void cloneCopiesFilesBeforeDbWrite() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        Dataset source = Dataset.builder()
                .id(id)
                .name("Src")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .build();
        stubCloneReads(id, source, DatasetVisibility.PUBLIC);
        when(authorResolver.getCreatedBy(any())).thenReturn("alice");
        when(datasetCloneService.deriveCloneName(any())).thenReturn("Src (clone)");
        when(transactionTimestampContext.getTimestamp()).thenReturn(1L);

        service.clone(id, new DatasetCloneRequestDto(), null);

        InOrder order = inOrder(datasetCloneService);
        order.verify(datasetCloneService).copyDatasetFiles(eq(id), any(UUID.class));
        order.verify(datasetCloneService)
                .cloneRowAndTestCases(
                        any(), any(), anyString(), any(), anyString(), anyLong(), eq(DatasetVisibility.PUBLIC));
    }

    @Test
    @DisplayName("clone deletes the copied files when the DB transaction fails")
    void cloneCleansUpFilesWhenTransactionFails() {
        UUID id = UUID.randomUUID();
        wireTxTemplate();
        Dataset source = Dataset.builder()
                .id(id)
                .name("Src")
                .visibility(DatasetVisibility.PUBLIC)
                .validationWarnings("[]")
                .build();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(source));
        when(authorResolver.getCreatedBy(any())).thenReturn("alice");
        when(datasetCloneService.deriveCloneName(any())).thenReturn("Src (clone)");
        when(transactionTimestampContext.getTimestamp()).thenReturn(1L);
        doThrow(new RuntimeException("write failed"))
                .when(datasetCloneService)
                .cloneRowAndTestCases(any(), any(), anyString(), any(), anyString(), anyLong(), any());

        assertThatThrownBy(() -> service.clone(id, new DatasetCloneRequestDto(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("write failed");

        verify(fileService).deleteAllByDatasetId(any(UUID.class));
    }

    @Test
    @DisplayName("clone on missing source throws EntityNotFoundException and copies no files")
    void cloneMissingSourceThrows() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.clone(id, new DatasetCloneRequestDto(), null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(id.toString());

        verify(datasetCloneService, never()).copyDatasetFiles(any(), any());
        verify(datasetCloneService, never()).cloneRowAndTestCases(any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName(
            "clone on a PRIVATE source throws PRIVATE_DATASET_REQUIRES_SUITE_BINDING before copying files or writing")
    void cloneRejectsPrivateSource() {
        UUID id = UUID.randomUUID();
        Dataset source = Dataset.builder()
                .id(id)
                .name("Src")
                .visibility(DatasetVisibility.PRIVATE)
                .validationWarnings("[]")
                .build();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.clone(id, new DatasetCloneRequestDto(), null))
                .isInstanceOf(DatasetVisibilityRuleException.class)
                .satisfies(ex -> assertThat(((DatasetVisibilityRuleException) ex).getErrorCode())
                        .isEqualTo(DatasetVisibilityErrorCode.PRIVATE_DATASET_REQUIRES_SUITE_BINDING));

        verify(datasetCloneService, never()).copyDatasetFiles(any(), any());
        verify(datasetCloneService, never()).cloneRowAndTestCases(any(), any(), any(), any(), any(), anyLong(), any());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubCloneReads(UUID sourceId, Dataset source, DatasetVisibility cloneVisibility) {
        when(datasetRepository.findById(any())).thenAnswer(inv -> {
            UUID arg = inv.getArgument(0);
            if (arg.equals(sourceId)) {
                return Optional.of(source);
            }
            return Optional.of(Dataset.builder()
                    .id(arg)
                    .name("clone")
                    .visibility(cloneVisibility)
                    .version(0L)
                    .validationWarnings("[]")
                    .build());
        });
    }

    private void wireTxTemplate() {
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(metaTransactionManager.getTransaction(any())).thenReturn(status);
    }
}
