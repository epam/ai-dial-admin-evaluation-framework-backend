package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.validation.RevalidationProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationException;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteMapper;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@DisplayName("TestSuiteCloneService — clone() orchestration")
@ExtendWith(MockitoExtension.class)
class TestSuiteCloneServiceTest {

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private TestSuiteMetricDefinitionRepository tsmdRepository;

    @Mock
    private TestSuiteMetricDefinitionService tsmdService;

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private DatasetCloneService datasetCloneService;

    @Mock
    private FileService fileService;

    @Mock
    private TestSuiteMapper testSuiteMapper;

    @Mock
    private SuiteValidationService suiteValidationService;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private AuthorResolver authorResolver;

    @Mock
    private RevalidationProperties revalidationProperties;

    @Mock
    private PlatformTransactionManager metaTransactionManager;

    @Mock
    private EndpointSchemaRefResolver endpointSchemaRefResolver;

    @Mock
    private ValidationWarningsSerializer warningsSerializer;

    private TestSuiteCloneService service;
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);

    private final UUID sourceId = UUID.randomUUID();
    private final UUID sourceDatasetId = UUID.randomUUID();
    private final String cloneName = "My Clone";

    @BeforeEach
    void setUp() {
        TransactionStatus txStatus = mock(TransactionStatus.class);
        lenient().when(metaTransactionManager.getTransaction(any())).thenReturn(txStatus);
        lenient().when(revalidationProperties.getBatchSize()).thenReturn(10);

        service = new TestSuiteCloneService(
                testSuiteRepository,
                tsmdRepository,
                tsmdService,
                datasetRepository,
                datasetCloneService,
                fileService,
                testSuiteMapper,
                suiteValidationService,
                datasetSchemaProvider,
                authorResolver,
                revalidationProperties,
                metaTransactionManager,
                clock,
                endpointSchemaRefResolver,
                warningsSerializer);
    }

    // -----------------------------------------------------------------------
    // (a) Successful clone — file cleanup must NOT run; revalidationTask is null
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(a) successful clone does not trigger file cleanup and returns null revalidationTask")
    void clone_doesNotCleanupFiles_onSuccess() {
        TestSuite source = buildSource();
        TestSuite newEntity = buildNewEntity(source);
        TestSuiteResponseDto expectedDto = TestSuiteResponseDto.builder()
                .id(newEntity.getId())
                .name(newEntity.getName())
                .build();

        setUpSuccessfulClone(source, newEntity, expectedDto);

        TestSuiteCloneRequestDto dto = cloneRequestWithNameOnly();
        TestSuiteUpdateResultDto result = service.clone(sourceId, dto, null);

        assertThat(result.getSuite()).isEqualTo(expectedDto);
        // Clone does not spawn an async revalidation task — validation is synchronous
        assertThat(result.getRevalidationTask()).isNull();
        verify(fileService, never()).deleteAllBySuiteId(any());
    }

    // -----------------------------------------------------------------------
    // (b) DataIntegrityViolationException → cleanup + UniqueConstraintViolationException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(b) DataIntegrityViolationException triggers cleanup and rethrows as unique violation")
    void clone_cleansUpFiles_andRethrows_asUniqueViolation_onDataIntegrityViolation() {
        TestSuite source = buildSource();
        UUID newId = UUID.randomUUID();
        TestSuite newEntity = buildNewEntityWithId(source, newId);

        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(authorResolver.getCreatedBy(any())).thenReturn("user");
        when(testSuiteMapper.toCloneEntity(any(), any(), any(), any())).thenReturn(newEntity);
        when(fileService.copyFilesBetweenSuites(any(), any())).thenReturn(List.of());
        setUpValidationChain(newEntity);

        // createWithId throws a unique constraint violation; batch loops are never reached
        SQLException sqlEx = new SQLException("Duplicate key", "23505");
        DataIntegrityViolationException div = new DataIntegrityViolationException("Duplicate", sqlEx);
        doThrow(div).when(testSuiteRepository).createWithId(any(), anyLong());

        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name(cloneName).build();
        assertThatThrownBy(() -> service.clone(sourceId, dto, null))
                .isInstanceOf(UniqueConstraintViolationException.class)
                .hasMessageContaining(cloneName);

        verify(fileService).deleteAllBySuiteId(newEntity.getId());
    }

    // -----------------------------------------------------------------------
    // (c) Unexpected runtime exception → cleanup + rethrow
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(c) unexpected runtime exception triggers cleanup and rethrows")
    void clone_cleansUpFiles_andRethrows_onUnexpectedException() {
        TestSuite source = buildSource();
        UUID newId = UUID.randomUUID();
        TestSuite newEntity = buildNewEntityWithId(source, newId);

        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(authorResolver.getCreatedBy(any())).thenReturn("user");
        when(testSuiteMapper.toCloneEntity(any(), any(), any(), any())).thenReturn(newEntity);
        when(fileService.copyFilesBetweenSuites(any(), any())).thenReturn(List.of());
        setUpValidationChain(newEntity);

        // createWithId throws; batch loops are never reached
        RuntimeException unexpected = new RuntimeException("Something went wrong");
        doThrow(unexpected).when(testSuiteRepository).createWithId(any(), anyLong());

        assertThatThrownBy(() -> service.clone(sourceId, cloneRequestWithNameOnly(), null))
                .isSameAs(unexpected);

        verify(fileService).deleteAllBySuiteId(newEntity.getId());
    }

    // -----------------------------------------------------------------------
    // (d) AuthorResolver called with the supplied JWT
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(d) AuthorResolver is called with the supplied JWT argument")
    void clone_callsAuthorResolver_withJwt() {
        TestSuite source = buildSource();
        TestSuite newEntity = buildNewEntity(source);
        TestSuiteResponseDto expectedDto = TestSuiteResponseDto.builder()
                .id(newEntity.getId())
                .name(newEntity.getName())
                .build();
        Jwt jwt = mock(Jwt.class);

        setUpSuccessfulClone(source, newEntity, expectedDto);
        when(authorResolver.getCreatedBy(jwt)).thenReturn("jwt-user");

        service.clone(sourceId, cloneRequestWithNameOnly(), jwt);

        verify(authorResolver).getCreatedBy(jwt);
    }

    // -----------------------------------------------------------------------
    // (e) TSMD cloning loop rewrites file refs in non-null fields; null stays null
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(e) TSMD cloning rewrites file refs in non-null configBindings, leaves null inputBindings null")
    void clone_tsmdLoop_rewritesNonNullBindings_andLeavesNullBindingsNull() {
        TestSuite source = buildSource();
        UUID newId = UUID.randomUUID();
        TestSuite newEntity = buildNewEntityWithId(source, newId);

        String srcPrefix = "@ef/suites/" + sourceId + "/";
        String tgtPrefix = "@ef/suites/" + newId + "/";

        TestSuiteMetricDefinition tsmdWithRef = TestSuiteMetricDefinition.builder()
                .id(UUID.randomUUID())
                .testSuiteId(sourceId)
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .name("M1")
                .configBindings("[{\"value\":\"" + srcPrefix + "cfg.json\"}]")
                .inputBindings(null)
                .enabled(true)
                .valid(true)
                .validationWarnings("[]")
                .build();

        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(authorResolver.getCreatedBy(any())).thenReturn("user");
        when(testSuiteMapper.toCloneEntity(any(), any(), any(), any())).thenReturn(newEntity);
        when(fileService.copyFilesBetweenSuites(any(), any())).thenReturn(List.of());
        setUpValidationChain(newEntity);
        // First call returns the TSMD, second call returns empty to terminate loop
        when(tsmdRepository.findBatchByTestSuiteId(eq(sourceId), eq(0), anyInt()))
                .thenReturn(List.of(tsmdWithRef));
        when(tsmdRepository.findBatchByTestSuiteId(eq(sourceId), eq(1), anyInt()))
                .thenReturn(List.of());
        when(testSuiteMapper.toDto(newEntity))
                .thenReturn(TestSuiteResponseDto.builder().build());

        service.clone(sourceId, cloneRequestWithNameOnly(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestSuiteMetricDefinition>> tsmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(tsmdRepository).batchInsert(tsmdCaptor.capture(), anyLong());

        List<TestSuiteMetricDefinition> inserted = tsmdCaptor.getValue();
        assertThat(inserted).hasSize(1);
        TestSuiteMetricDefinition cloned = inserted.get(0);
        // configBindings rewritten
        assertThat(cloned.getConfigBindings()).contains(tgtPrefix);
        assertThat(cloned.getConfigBindings()).doesNotContain(srcPrefix);
        // inputBindings was null → stays null
        assertThat(cloned.getInputBindings()).isNull();
        // new UUID assigned
        assertThat(cloned.getId()).isNotEqualTo(tsmdWithRef.getId());
        // linked to new suite
        assertThat(cloned.getTestSuiteId()).isEqualTo(newId);
    }

    // -----------------------------------------------------------------------
    // (f) Unbound source (datasetId == null) → validates against empty schema, no NPE
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(f) cloning an unbound source validates against empty schema without calling DatasetSchemaProvider")
    void clone_unboundSource_validatesAgainstEmptySchema_andNeverResolvesSchema() {
        TestSuite source = buildSource();
        source.setDatasetId(null);
        UUID newId = UUID.randomUUID();
        TestSuite newEntity = buildNewEntityWithId(source, newId);
        newEntity.setDatasetId(null);

        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(authorResolver.getCreatedBy(any())).thenReturn("user");
        when(testSuiteMapper.toCloneEntity(any(), any(), any(), any())).thenReturn(newEntity);

        TestSuiteRequestDto requestDto = TestSuiteRequestDto.builder()
                .name(newEntity.getName())
                .suiteType(newEntity.getSuiteType())
                .datasetId(null)
                .inputBindings(List.of())
                .responseColumns(List.of())
                .build();
        when(testSuiteMapper.toRequestDto(newEntity)).thenReturn(requestDto);
        when(endpointSchemaRefResolver.resolve(any())).thenAnswer(inv -> inv.getArgument(0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = ArgumentCaptor.forClass(List.class);
        when(suiteValidationService.validateSuite(any(TestSuiteRequestDto.class), isNull(), schemaCaptor.capture()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
        when(warningsSerializer.serializeWarnings(anyList())).thenReturn("[]");
        when(tsmdRepository.findBatchByTestSuiteId(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(testSuiteMapper.toDto(newEntity))
                .thenReturn(TestSuiteResponseDto.builder().build());

        TestSuiteUpdateResultDto result = service.clone(sourceId, cloneRequestWithNameOnly(), null);

        assertThat(result.getSuite()).isNotNull();
        // No dataset bound → schema resolution is skipped entirely (the NPE source)
        verify(datasetSchemaProvider, never()).getSchema(any());
        // Validation still runs, against an empty schema
        assertThat(schemaCaptor.getValue()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // (g) Vanilla clone (no override) copies source TSMD validity verbatim, no recompute
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(g) vanilla clone copies source TSMD valid/warnings verbatim and never recomputes")
    void clone_vanilla_copiesTsmdValidityVerbatim_andDoesNotRevalidate() {
        TestSuite source = buildSource();
        UUID newId = UUID.randomUUID();
        TestSuite newEntity = buildNewEntityWithId(source, newId);

        TestSuiteMetricDefinition validTsmd = TestSuiteMetricDefinition.builder()
                .id(UUID.randomUUID())
                .testSuiteId(sourceId)
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .name("valid")
                .enabled(true)
                .valid(true)
                .validationWarnings("[]")
                .build();
        TestSuiteMetricDefinition invalidTsmd = TestSuiteMetricDefinition.builder()
                .id(UUID.randomUUID())
                .testSuiteId(sourceId)
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .name("invalid")
                .enabled(true)
                .valid(false)
                .validationWarnings("[{\"code\":\"REQUIRED\"}]")
                .build();

        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(authorResolver.getCreatedBy(any())).thenReturn("user");
        when(testSuiteMapper.toCloneEntity(any(), any(), any(), any())).thenReturn(newEntity);
        when(fileService.copyFilesBetweenSuites(any(), any())).thenReturn(List.of());
        setUpValidationChain(newEntity);
        when(tsmdRepository.findBatchByTestSuiteId(eq(sourceId), eq(0), anyInt()))
                .thenReturn(List.of(validTsmd, invalidTsmd));
        when(tsmdRepository.findBatchByTestSuiteId(eq(sourceId), eq(2), anyInt()))
                .thenReturn(List.of());
        when(testSuiteMapper.toDto(newEntity))
                .thenReturn(TestSuiteResponseDto.builder().build());

        service.clone(sourceId, cloneRequestWithNameOnly(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestSuiteMetricDefinition>> tsmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(tsmdRepository).batchInsert(tsmdCaptor.capture(), anyLong());
        List<TestSuiteMetricDefinition> inserted = tsmdCaptor.getValue();

        TestSuiteMetricDefinition clonedValid = findByName(inserted, "valid");
        assertThat(clonedValid.isValid()).isTrue();
        assertThat(clonedValid.getValidationWarnings()).isEqualTo("[]");

        TestSuiteMetricDefinition clonedInvalid = findByName(inserted, "invalid");
        assertThat(clonedInvalid.isValid()).isFalse();
        assertThat(clonedInvalid.getValidationWarnings()).isEqualTo("[{\"code\":\"REQUIRED\"}]");

        // No override → no recompute
        verify(tsmdService, never()).revalidateAllForSuite(any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // (h) datasetId override → recompute against the override dataset's schema
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(h) datasetId override inserts placeholder-invalid TSMDs and recomputes against override schema")
    void clone_datasetIdOverride_recomputesTsmdsAgainstOverrideSchema() {
        UUID overrideDatasetId = UUID.randomUUID();
        String overrideSchemaJson = "[{\"name\":\"col\",\"type\":\"STRING\"}]";

        TestSuite source = buildSource();
        UUID newId = UUID.randomUUID();
        TestSuite newEntity = buildNewEntityWithId(source, newId);
        newEntity.setDatasetId(overrideDatasetId);

        TestSuiteMetricDefinition sourceTsmd = TestSuiteMetricDefinition.builder()
                .id(UUID.randomUUID())
                .testSuiteId(sourceId)
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .name("M1")
                .enabled(true)
                .valid(true)
                .validationWarnings("[]")
                .build();

        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        // Source dataset lookup at the top of clone() must be stubbed explicitly so strict stubbing
        // does not flag the override-id findById stub when findById is also called for the source id.
        when(datasetRepository.findById(sourceDatasetId)).thenReturn(Optional.empty());
        when(datasetRepository.existsById(overrideDatasetId)).thenReturn(true);
        when(datasetRepository.findById(overrideDatasetId))
                .thenReturn(Optional.of(Dataset.builder()
                        .id(overrideDatasetId)
                        .testCaseSchema(overrideSchemaJson)
                        .build()));
        when(authorResolver.getCreatedBy(any())).thenReturn("user");
        when(testSuiteMapper.toCloneEntity(any(), any(), any(), any())).thenReturn(newEntity);
        when(fileService.copyFilesBetweenSuites(any(), any())).thenReturn(List.of());
        setUpValidationChain(newEntity);
        when(tsmdRepository.findBatchByTestSuiteId(eq(sourceId), eq(0), anyInt()))
                .thenReturn(List.of(sourceTsmd));
        when(tsmdRepository.findBatchByTestSuiteId(eq(sourceId), eq(1), anyInt()))
                .thenReturn(List.of());
        when(testSuiteMapper.toDto(newEntity))
                .thenReturn(TestSuiteResponseDto.builder().build());

        TestSuiteCloneRequestDto dto = TestSuiteCloneRequestDto.builder()
                .name(cloneName)
                .datasetId(overrideDatasetId)
                .build();
        service.clone(sourceId, dto, null);

        // Inserted with deterministic invalid placeholder; recompute will fix it up
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestSuiteMetricDefinition>> tsmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(tsmdRepository).batchInsert(tsmdCaptor.capture(), anyLong());
        assertThat(tsmdCaptor.getValue().get(0).isValid()).isFalse();

        // Recompute runs after inserts against the override dataset's schema + the suite's response columns
        verify(tsmdService).revalidateAllForSuite(newId, overrideSchemaJson, newEntity.getResponseColumns());
    }

    // -----------------------------------------------------------------------
    // (i) responseColumns override → recompute even without a datasetId override
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("(i) responseColumns override recomputes TSMDs against the inherited dataset schema")
    void clone_responseColumnsOverride_recomputesTsmds() {
        String inheritedSchemaJson = "[{\"name\":\"q\",\"type\":\"STRING\"}]";

        TestSuite source = buildSource();
        UUID newId = UUID.randomUUID();
        TestSuite newEntity = buildNewEntityWithId(source, newId);

        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        // Source dataset resolves to a non-PRIVATE dataset, so no private-dataset auto-clone path
        when(datasetRepository.findById(sourceDatasetId))
                .thenReturn(Optional.of(Dataset.builder()
                        .id(sourceDatasetId)
                        .testCaseSchema(inheritedSchemaJson)
                        .build()));
        when(authorResolver.getCreatedBy(any())).thenReturn("user");
        when(testSuiteMapper.toCloneEntity(any(), any(), any(), any())).thenReturn(newEntity);
        when(fileService.copyFilesBetweenSuites(any(), any())).thenReturn(List.of());
        setUpValidationChain(newEntity);
        when(tsmdRepository.findBatchByTestSuiteId(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(testSuiteMapper.toDto(newEntity))
                .thenReturn(TestSuiteResponseDto.builder().build());

        // Non-null responseColumns override triggers TSMD revalidation
        TestSuiteCloneRequestDto dto = TestSuiteCloneRequestDto.builder()
                .name(cloneName)
                .responseColumns(List.of())
                .build();
        service.clone(sourceId, dto, null);

        verify(tsmdService).revalidateAllForSuite(newId, inheritedSchemaJson, newEntity.getResponseColumns());
    }

    private static TestSuiteMetricDefinition findByName(List<TestSuiteMetricDefinition> tsmds, String name) {
        return tsmds.stream().filter(t -> name.equals(t.getName())).findFirst().orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Source not found → 404
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("throws EntityNotFoundException when source suite does not exist")
    void clone_throws404_whenSourceNotFound() {
        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.clone(sourceId, cloneRequestWithNameOnly(), null))
                .isInstanceOf(EntityNotFoundException.class);

        verify(fileService, never()).copyFilesBetweenSuites(any(), any());
        verify(fileService, never()).deleteAllBySuiteId(any());
    }

    // -----------------------------------------------------------------------
    // Optional datasetId override absent → 404
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("throws EntityNotFoundException when DTO datasetId override does not exist")
    void clone_throws404_whenOverrideDatasetMissing() {
        UUID overrideDatasetId = UUID.randomUUID();
        TestSuite source = buildSource();

        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(datasetRepository.existsById(overrideDatasetId)).thenReturn(false);

        TestSuiteCloneRequestDto dto = TestSuiteCloneRequestDto.builder()
                .name(cloneName)
                .datasetId(overrideDatasetId)
                .build();

        assertThatThrownBy(() -> service.clone(sourceId, dto, null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(overrideDatasetId.toString());

        verify(fileService, never()).copyFilesBetweenSuites(any(), any());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TestSuite buildSource() {
        return TestSuite.builder()
                .id(sourceId)
                .name("Source Suite")
                .description("Source desc")
                .suiteType(SuiteType.DEPLOYMENT)
                .datasetId(sourceDatasetId)
                .disabledTestCaseIds("[]")
                .deploymentRef("{\"id\":\"d1\"}")
                .endpointRef("{\"method\":\"POST\",\"relativeUrlPattern\":\"/v1/chat\"}")
                .responseColumns("[]")
                .inputBindings("[]")
                .valid(true)
                .validationWarnings("[]")
                .version(1L)
                .createdBy("original")
                .build();
    }

    private TestSuite buildNewEntity(TestSuite source) {
        return buildNewEntityWithId(source, UUID.randomUUID());
    }

    private TestSuite buildNewEntityWithId(TestSuite source, UUID newId) {
        return TestSuite.builder()
                .id(newId)
                .name(cloneName)
                .description(source.getDescription())
                .suiteType(source.getSuiteType())
                .datasetId(source.getDatasetId())
                .disabledTestCaseIds("[]")
                .deploymentRef(source.getDeploymentRef())
                .endpointRef(source.getEndpointRef())
                .responseColumns(source.getResponseColumns())
                .inputBindings(source.getInputBindings())
                .version(0L)
                .createdBy("user")
                .build();
    }

    private void setUpSuccessfulClone(TestSuite source, TestSuite newEntity, TestSuiteResponseDto responseDto) {
        when(testSuiteRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(authorResolver.getCreatedBy(any())).thenReturn("user");
        when(testSuiteMapper.toCloneEntity(any(), any(), any(), any())).thenReturn(newEntity);
        when(fileService.copyFilesBetweenSuites(any(), any())).thenReturn(List.of());
        setUpValidationChain(newEntity);
        when(tsmdRepository.findBatchByTestSuiteId(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(testSuiteMapper.toDto(newEntity)).thenReturn(responseDto);
    }

    private void setUpValidationChain(TestSuite entity) {
        TestSuiteRequestDto requestDto = TestSuiteRequestDto.builder()
                .name(entity.getName())
                .suiteType(entity.getSuiteType())
                .datasetId(entity.getDatasetId())
                .inputBindings(List.of())
                .responseColumns(List.of())
                .build();
        when(testSuiteMapper.toRequestDto(entity)).thenReturn(requestDto);
        when(endpointSchemaRefResolver.resolve(any())).thenAnswer(inv -> inv.getArgument(0));
        when(datasetSchemaProvider.getSchema(entity.getDatasetId())).thenReturn(List.of());
        when(suiteValidationService.validateSuite(any(TestSuiteRequestDto.class), isNull(), anyList()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
        when(warningsSerializer.serializeWarnings(anyList())).thenReturn("[]");
    }

    private TestSuiteCloneRequestDto cloneRequestWithNameOnly() {
        return TestSuiteCloneRequestDto.builder().name(cloneName).build();
    }
}
