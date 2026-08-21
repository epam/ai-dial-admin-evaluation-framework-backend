package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.DatasetDetachRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.job.RunnableTestCaseSelector;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteService Unit Tests")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestSuiteServiceTest {

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private DatasetQueryService datasetQueryService;

    @Mock
    private DatasetCascadeService datasetCascadeService;

    @Mock
    private DatasetCloneService datasetCloneService;

    @Mock
    private TestSuiteMapper testSuiteMapper;

    @Mock
    private JsonbMapper jsonbMapper;

    @Mock
    private AuthorResolver authorResolver;

    @Mock
    private EndpointSchemaRefResolver endpointSchemaRefResolver;

    @Mock
    private SuiteValidationService suiteValidationService;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private RunnableTestCaseSelector runnableTestCaseSelector;

    @Mock
    private TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;

    @Mock
    private FileService fileService;

    @Mock
    private Clock clock;

    @Mock
    private PlatformTransactionManager metaTransactionManager;

    @Mock
    private SortParser sortParser;

    @Mock
    private FilterParser filterParser;

    @Mock
    private ValidationWarningsSerializer warningsSerializer;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TestSuiteRequestValidator testSuiteRequestValidator;

    @Mock
    private ResponseColumnUnionResolver responseColumnUnionResolver;

    private TestSuiteService service;

    @BeforeEach
    void setUp() {
        service = new TestSuiteService(
                testSuiteRepository,
                datasetQueryService,
                datasetCascadeService,
                datasetCloneService,
                testSuiteMapper,
                jsonbMapper,
                authorResolver,
                endpointSchemaRefResolver,
                suiteValidationService,
                datasetSchemaProvider,
                runnableTestCaseSelector,
                testSuiteMetricDefinitionService,
                fileService,
                clock,
                metaTransactionManager,
                sortParser,
                filterParser,
                warningsSerializer,
                objectMapper,
                testSuiteRequestValidator,
                responseColumnUnionResolver);
    }

    @Test
    @DisplayName("detachDataset deletes the copied files when the DB transaction fails")
    void shouldDeleteCopiedFilesWhenTransactionFails() {
        UUID suiteId = UUID.randomUUID();
        UUID sourceDatasetId = UUID.randomUUID();

        TestSuite suite = TestSuite.builder()
                .id(suiteId)
                .datasetId(sourceDatasetId)
                .version(1L)
                .build();
        Dataset source = Dataset.builder()
                .id(sourceDatasetId)
                .name("Source")
                .visibility(DatasetVisibility.PUBLIC)
                .build();

        when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(suite));
        when(datasetQueryService.findById(sourceDatasetId)).thenReturn(Optional.of(source));
        // The in-transaction clone blows up, so the surrounding TransactionTemplate.execute(...)
        // rethrows, leaving txSucceeded=false and triggering the file-cleanup branch.
        doThrow(new RuntimeException("boom"))
                .when(datasetCloneService)
                .cloneRowAndTestCases(any(), any(), any(), any(), any(), anyLong(), any());

        assertThatThrownBy(() -> service.detachDataset(
                        suiteId, DatasetDetachRequestDto.builder().build(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // The pre-transaction copy targeted a freshly generated dataset id; cleanup must remove it.
        verify(fileService, times(1)).deleteAllByDatasetId(any(UUID.class));
    }

    @Test
    @DisplayName("create() resolves $ref for request #0's endpointRef and every additional request's endpointRef")
    void shouldResolveEndpointRefForRequest0AndEveryAdditionalRequest() {
        EndpointContractDto request0Endpoint = EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/v1/chat")
                .build();
        EndpointContractDto additionalEndpoint = EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/v1/second")
                .build();
        RequestDefinitionDto additionalRequest = RequestDefinitionDto.builder()
                .name("second")
                .endpointRef(additionalEndpoint)
                .build();
        TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .name("Suite")
                .endpointRef(request0Endpoint)
                .additionalRequests(List.of(additionalRequest))
                .build();

        // Identity resolver — this test only asserts WHICH endpointRefs are passed to the resolver,
        // not its own $ref-inlining logic (covered separately for the resolver itself).
        when(endpointSchemaRefResolver.resolve(any())).thenAnswer(inv -> inv.getArgument(0));
        when(testSuiteMapper.toEntity(any(), any()))
                .thenReturn(TestSuite.builder().build());
        when(suiteValidationService.validateSuite(any(), any(), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());

        service.create(dto, null);

        // Request #0's endpointRef and the additional request's endpointRef are each resolved once.
        verify(endpointSchemaRefResolver).resolve(request0Endpoint);
        verify(endpointSchemaRefResolver).resolve(additionalEndpoint);
    }
}
