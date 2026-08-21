package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.job.PerTurnBindingDetector;
import com.epam.aidial.evaluation.runner.job.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.util.TestCaseTurnsCsvSerializer;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the DB-backed Try-It-Out overload loads the suite/test-case and delegates the actual
 * resolution to {@link RequestResolver}. The resolution logic itself is covered by
 * {@code RequestResolverTest} in the shared module.
 */
@DisplayName("ResolvedRequestService")
@ExtendWith(MockitoExtension.class)
class ResolvedRequestServiceTest {

    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final UUID TEST_CASE_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private JsonbMapper jsonbMapper;

    @Mock
    private ValidationWarningsSerializer warningsSerializer;

    @Mock
    private RequestResolver requestResolver;

    @Mock
    private TestCaseTurnsCsvSerializer turnsSerializer;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private PerTurnBindingDetector perTurnBindingDetector;

    @InjectMocks
    private ResolvedRequestService service;

    @Test
    @DisplayName("loads suite and test case, then delegates resolution to RequestResolver")
    void resolveRequest_delegatesToRequestResolver() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder()
                .id(TEST_CASE_ID)
                .data("{\"field\":\"value\"}")
                .build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestTemplateDto template = RequestTemplateDto.builder().build();
        List<InputBindingDto> bindings = List.of();
        Map<String, Object> data = Map.of("field", "value");
        when(jsonbMapper.mapRequestTemplate("{}")).thenReturn(template);
        when(jsonbMapper.mapInputBindings("[]")).thenReturn(bindings);
        when(warningsSerializer.deserializeMap("{\"field\":\"value\"}")).thenReturn(data);

        ResolvedRequestDto expected =
                ResolvedRequestDto.builder().url("/resolved").build();
        when(requestResolver.resolve(template, bindings, data)).thenReturn(expected);

        ResolvedRequestDto result = service.resolveRequest(SUITE_ID, TEST_CASE_ID);

        assertThat(result).isSameAs(expected);
        verify(requestResolver).resolve(template, bindings, data);
    }

    @Test
    @DisplayName("throws EntityNotFoundException when suite does not exist")
    void resolveRequest_throwsWhenSuiteMissing() {
        UUID missingSuiteId = UUID.randomUUID();
        when(testSuiteRepository.findById(missingSuiteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveRequest(missingSuiteId, TEST_CASE_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("TestSuite not found");
    }

    @Test
    @DisplayName("throws EntityNotFoundException when test case does not exist in the suite's dataset")
    void resolveRequest_throwsWhenTestCaseMissing() {
        TestSuite suite = TestSuite.builder().id(SUITE_ID).datasetId(DATASET_ID).build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        when(testCaseRepository.findByIdAndDatasetId(eq(TEST_CASE_ID), eq(DATASET_ID)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveRequest(SUITE_ID, TEST_CASE_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("TestCase not found");

        verify(requestResolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("requestIndex 1 resolves additionalRequests[0]'s template and bindings")
    void resolveRequest_selectsAdditionalRequestByIndex() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .additionalRequests("[{\"name\":\"second\"}]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder().id(TEST_CASE_ID).data("{}").build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestTemplateDto additionalTemplate =
                RequestTemplateDto.builder().urlTemplate("/second").build();
        List<InputBindingDto> additionalBindings = List.of();
        RequestDefinitionDto additionalRequest = RequestDefinitionDto.builder()
                .name("second")
                .requestTemplate(additionalTemplate)
                .inputBindings(additionalBindings)
                .build();
        when(jsonbMapper.mapAdditionalRequests("[{\"name\":\"second\"}]")).thenReturn(List.of(additionalRequest));
        Map<String, Object> data = Map.of();
        when(warningsSerializer.deserializeMap("{}")).thenReturn(data);

        ResolvedRequestDto expected =
                ResolvedRequestDto.builder().url("/second").build();
        when(requestResolver.resolve(additionalTemplate, additionalBindings, data))
                .thenReturn(expected);

        ResolvedRequestDto result = service.resolveRequest(SUITE_ID, TEST_CASE_ID, 1);

        assertThat(result).isSameAs(expected);
        verify(requestResolver).resolve(additionalTemplate, additionalBindings, data);
    }

    @Test
    @DisplayName("out-of-range requestIndex is rejected with a ValidationException")
    void resolveRequest_rejectsOutOfRangeIndex() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .additionalRequests("[]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder().id(TEST_CASE_ID).data("{}").build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));
        when(jsonbMapper.mapAdditionalRequests("[]")).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveRequest(SUITE_ID, TEST_CASE_ID, 5))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestIndex");

        verify(requestResolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("negative requestIndex is rejected with a ValidationException")
    void resolveRequest_rejectsNegativeIndex() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .additionalRequests("[]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder().id(TEST_CASE_ID).data("{}").build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));
        when(jsonbMapper.mapAdditionalRequests("[]")).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveRequest(SUITE_ID, TEST_CASE_ID, -1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requestIndex");

        verify(requestResolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("planChain plans a single turn from shared data when multiTurnData is absent")
    void planChain_singleTurn_whenNoMultiTurnData() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder()
                .id(TEST_CASE_ID)
                .data("{\"field\":\"value\"}")
                .build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestTemplateDto template = RequestTemplateDto.builder().build();
        List<InputBindingDto> bindings = List.of();
        Map<String, Object> data = Map.of("field", "value");
        when(jsonbMapper.mapRequestTemplate("{}")).thenReturn(template);
        when(jsonbMapper.mapInputBindings("[]")).thenReturn(bindings);
        when(jsonbMapper.mapResponseColumns("[]")).thenReturn(List.of());
        when(warningsSerializer.deserializeMap("{\"field\":\"value\"}")).thenReturn(data);
        when(turnsSerializer.deserializeTurns(null)).thenReturn(null);

        ResolvedRequestService.ChainPlan plan = service.planChain(SUITE_ID, TEST_CASE_ID, null);

        assertThat(plan.requestPlans()).hasSize(1);
        assertThat(plan.requestPlans().getFirst().turnDataList()).containsExactly(data);
        verify(datasetSchemaProvider, never()).getSchema(any());
        verify(perTurnBindingDetector, never()).referencesPerTurnField(any(), any());
    }

    @Test
    @DisplayName("planChain collapses to a single turn when no effective binding targets a perTurn field")
    void planChain_collapsesToSingleTurn_whenNoPerTurnBinding() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder()
                .id(TEST_CASE_ID)
                .data("{\"field\":\"value\"}")
                .multiTurnData("[{\"turn\":1},{\"turn\":2}]")
                .build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestTemplateDto template = RequestTemplateDto.builder().build();
        List<InputBindingDto> bindings = List.of();
        Map<String, Object> sharedData = Map.of("field", "value");
        List<Map<String, Object>> turns = List.of(Map.of("turn", 1), Map.of("turn", 2));
        List<FieldDefinitionDto> schema = List.of();
        when(jsonbMapper.mapRequestTemplate("{}")).thenReturn(template);
        when(jsonbMapper.mapInputBindings("[]")).thenReturn(bindings);
        when(jsonbMapper.mapResponseColumns("[]")).thenReturn(List.of());
        when(warningsSerializer.deserializeMap("{\"field\":\"value\"}")).thenReturn(sharedData);
        when(turnsSerializer.deserializeTurns("[{\"turn\":1},{\"turn\":2}]")).thenReturn(turns);
        when(datasetSchemaProvider.getSchema(DATASET_ID)).thenReturn(schema);
        when(perTurnBindingDetector.referencesPerTurnField(bindings, schema)).thenReturn(false);

        ResolvedRequestService.ChainPlan plan = service.planChain(SUITE_ID, TEST_CASE_ID, null);

        assertThat(plan.requestPlans().getFirst().turnDataList()).containsExactly(sharedData);
    }

    @Test
    @DisplayName("planChain builds one merged turn per multiTurnData entry when a perTurn binding is present")
    void planChain_buildsPerTurnList_whenPerTurnBindingPresent() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder()
                .id(TEST_CASE_ID)
                .data("{\"shared\":\"s\"}")
                .multiTurnData("[{\"question\":\"q1\"},{\"question\":\"q2\",\"shared\":\"overridden\"}]")
                .build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestTemplateDto template = RequestTemplateDto.builder().build();
        List<InputBindingDto> bindings = List.of();
        Map<String, Object> sharedData = Map.of("shared", "s");
        Map<String, Object> turn1 = Map.of("question", "q1");
        Map<String, Object> turn2 = Map.of("question", "q2", "shared", "overridden");
        List<FieldDefinitionDto> schema = List.of();
        when(jsonbMapper.mapRequestTemplate("{}")).thenReturn(template);
        when(jsonbMapper.mapInputBindings("[]")).thenReturn(bindings);
        when(jsonbMapper.mapResponseColumns("[]")).thenReturn(List.of());
        when(warningsSerializer.deserializeMap("{\"shared\":\"s\"}")).thenReturn(sharedData);
        when(turnsSerializer.deserializeTurns(any())).thenReturn(List.of(turn1, turn2));
        when(datasetSchemaProvider.getSchema(DATASET_ID)).thenReturn(schema);
        when(perTurnBindingDetector.referencesPerTurnField(bindings, schema)).thenReturn(true);

        ResolvedRequestService.ChainPlan plan = service.planChain(SUITE_ID, TEST_CASE_ID, null);

        assertThat(plan.requestPlans().getFirst().turnDataList())
                .containsExactly(
                        Map.of("shared", "s", "question", "q1"), Map.of("shared", "overridden", "question", "q2"));
    }

    @Test
    @DisplayName("planChain builds ordered specs: #0 from suite fields with requestName, then additionalRequests")
    void planChain_buildsOrderedSpecs_withSuiteRequestNameThreaded() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestName("primary")
                .endpointRef("{\"method\":\"POST\"}")
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .additionalRequests("[{\"name\":\"second\"}]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder().id(TEST_CASE_ID).data("{}").build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        EndpointContractDto suiteEndpoint = EndpointContractDto.builder().build();
        RequestTemplateDto suiteTemplate = RequestTemplateDto.builder().build();
        List<InputBindingDto> suiteBindings = List.of();
        List<ResponseColumnDefinitionDto> suiteColumns =
                List.of(ResponseColumnDefinitionDto.builder().name("col").build());
        when(jsonbMapper.mapEndpointContract("{\"method\":\"POST\"}")).thenReturn(suiteEndpoint);
        when(jsonbMapper.mapRequestTemplate("{}")).thenReturn(suiteTemplate);
        when(jsonbMapper.mapInputBindings("[]")).thenReturn(suiteBindings);
        when(jsonbMapper.mapResponseColumns("[]")).thenReturn(suiteColumns);

        EndpointContractDto additionalEndpoint = EndpointContractDto.builder().build();
        RequestTemplateDto additionalTemplate =
                RequestTemplateDto.builder().urlTemplate("/second").build();
        List<InputBindingDto> additionalBindings =
                List.of(InputBindingDto.builder().build());
        List<ResponseColumnDefinitionDto> additionalColumns =
                List.of(ResponseColumnDefinitionDto.builder().name("col2").build());
        RequestDefinitionDto additionalRequest = RequestDefinitionDto.builder()
                .name("second")
                .endpointRef(additionalEndpoint)
                .requestTemplate(additionalTemplate)
                .inputBindings(additionalBindings)
                .responseColumns(additionalColumns)
                .build();
        Map<String, Object> data = Map.of("field", "value");
        when(warningsSerializer.deserializeMap("{}")).thenReturn(data);
        when(turnsSerializer.deserializeTurns(null)).thenReturn(null);

        ResolvedRequestService.ChainPlan plan = service.planChain(SUITE_ID, TEST_CASE_ID, List.of(additionalRequest));

        assertThat(plan.requestPlans()).hasSize(2);
        RequestExecutionSpec first = plan.requestPlans().get(0).spec();
        assertThat(first.requestIndex()).isZero();
        assertThat(first.totalRequests()).isEqualTo(2);
        assertThat(first.name()).isEqualTo("primary");
        assertThat(first.endpointRef()).isSameAs(suiteEndpoint);
        assertThat(first.requestTemplate()).isSameAs(suiteTemplate);
        assertThat(first.inputBindings()).isSameAs(suiteBindings);
        assertThat(first.responseColumns()).isSameAs(suiteColumns);
        RequestExecutionSpec second = plan.requestPlans().get(1).spec();
        assertThat(second.requestIndex()).isEqualTo(1);
        assertThat(second.totalRequests()).isEqualTo(2);
        assertThat(second.name()).isEqualTo("second");
        assertThat(second.endpointRef()).isSameAs(additionalEndpoint);
        assertThat(second.requestTemplate()).isSameAs(additionalTemplate);
        assertThat(second.inputBindings()).isSameAs(additionalBindings);
        assertThat(second.responseColumns()).isSameAs(additionalColumns);
        assertThat(plan.totalInvocations()).isEqualTo(2);
    }

    @Test
    @DisplayName("planChain normalizes an additional request's null bindings/columns to empty lists")
    void planChain_normalizesNullBindingsAndColumns() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .additionalRequests("[{}]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder().id(TEST_CASE_ID).data("{}").build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestDefinitionDto bare = RequestDefinitionDto.builder().build();
        when(warningsSerializer.deserializeMap("{}")).thenReturn(Map.of());
        when(turnsSerializer.deserializeTurns(null)).thenReturn(null);

        ResolvedRequestService.ChainPlan plan = service.planChain(SUITE_ID, TEST_CASE_ID, List.of(bare));

        RequestExecutionSpec chained = plan.requestPlans().get(1).spec();
        assertThat(chained.inputBindings()).isEmpty();
        assertThat(chained.responseColumns()).isEmpty();
    }

    @Test
    @DisplayName("planChain decides turn count per request from that request's own bindings, loading the schema once")
    void planChain_perRequestTurnDetection_loadsSchemaOnce() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .additionalRequests("[{\"name\":\"second\"}]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder()
                .id(TEST_CASE_ID)
                .data("{\"shared\":\"s\"}")
                .multiTurnData("[{\"question\":\"q1\"},{\"question\":\"q2\"}]")
                .build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        List<InputBindingDto> suiteBindings =
                List.of(InputBindingDto.builder().templateVariable("perTurnVar").build());
        List<InputBindingDto> additionalBindings =
                List.of(InputBindingDto.builder().templateVariable("sharedVar").build());
        RequestDefinitionDto additionalRequest = RequestDefinitionDto.builder()
                .name("second")
                .requestTemplate(RequestTemplateDto.builder().build())
                .inputBindings(additionalBindings)
                .build();
        when(jsonbMapper.mapRequestTemplate("{}"))
                .thenReturn(RequestTemplateDto.builder().build());
        when(jsonbMapper.mapInputBindings("[]")).thenReturn(suiteBindings);
        when(jsonbMapper.mapResponseColumns("[]")).thenReturn(List.of());

        Map<String, Object> sharedData = Map.of("shared", "s");
        when(warningsSerializer.deserializeMap("{\"shared\":\"s\"}")).thenReturn(sharedData);
        when(turnsSerializer.deserializeTurns("[{\"question\":\"q1\"},{\"question\":\"q2\"}]"))
                .thenReturn(List.of(Map.of("question", "q1"), Map.of("question", "q2")));

        List<FieldDefinitionDto> schema = List.of();
        when(datasetSchemaProvider.getSchema(DATASET_ID)).thenReturn(schema);
        when(perTurnBindingDetector.referencesPerTurnField(suiteBindings, schema))
                .thenReturn(true);
        when(perTurnBindingDetector.referencesPerTurnField(additionalBindings, schema))
                .thenReturn(false);

        ResolvedRequestService.ChainPlan plan = service.planChain(SUITE_ID, TEST_CASE_ID, List.of(additionalRequest));

        assertThat(plan.requestPlans().get(0).turnDataList())
                .containsExactly(Map.of("shared", "s", "question", "q1"), Map.of("shared", "s", "question", "q2"));
        assertThat(plan.requestPlans().get(1).turnDataList()).containsExactly(sharedData);
        assertThat(plan.totalInvocations()).isEqualTo(3);
        verify(datasetSchemaProvider, times(1)).getSchema(DATASET_ID);
    }

    @Test
    @DisplayName("planChain skips the schema read entirely when the test case has no readable turns")
    void planChain_lazySchemaLoading_skippedWithoutReadableTurns() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .additionalRequests("[{\"name\":\"second\"}]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder()
                .id(TEST_CASE_ID)
                .data("{\"field\":\"value\"}")
                .multiTurnData("[]")
                .build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestDefinitionDto additionalRequest =
                RequestDefinitionDto.builder().name("second").build();
        Map<String, Object> data = Map.of("field", "value");
        when(warningsSerializer.deserializeMap("{\"field\":\"value\"}")).thenReturn(data);
        when(turnsSerializer.deserializeTurns("[]")).thenReturn(List.of());

        ResolvedRequestService.ChainPlan plan = service.planChain(SUITE_ID, TEST_CASE_ID, List.of(additionalRequest));

        assertThat(plan.requestPlans()).hasSize(2);
        assertThat(plan.requestPlans().get(0).turnDataList()).containsExactly(data);
        assertThat(plan.requestPlans().get(1).turnDataList()).containsExactly(data);
        verify(datasetSchemaProvider, never()).getSchema(any());
        verify(perTurnBindingDetector, never()).referencesPerTurnField(any(), any());
    }

    @Test
    @DisplayName("planChain throws EntityNotFoundException when the test case does not exist")
    void planChain_throwsWhenTestCaseMissing() {
        TestSuite suite = TestSuite.builder().id(SUITE_ID).datasetId(DATASET_ID).build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.planChain(SUITE_ID, TEST_CASE_ID, null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("TestCase not found");
    }

    @Test
    @DisplayName("planChainForVariables plans one empty-data turn per request and never touches TestCaseRepository")
    void planChainForVariables_singleTurnPerRequest_noTestCaseAccess() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestName("primary")
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .additionalRequests("[{\"name\":\"second\"}]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));

        RequestDefinitionDto additionalRequest =
                RequestDefinitionDto.builder().name("second").build();

        ResolvedRequestService.ChainPlan plan = service.planChainForVariables(SUITE_ID, List.of(additionalRequest));

        assertThat(plan.requestPlans()).hasSize(2);
        assertThat(plan.requestPlans().get(0).spec().name()).isEqualTo("primary");
        assertThat(plan.requestPlans().get(1).spec().name()).isEqualTo("second");
        assertThat(plan.requestPlans().get(0).turnDataList()).containsExactly(Map.of());
        assertThat(plan.requestPlans().get(1).turnDataList()).containsExactly(Map.of());
        assertThat(plan.totalInvocations()).isEqualTo(2);
        verifyNoInteractions(testCaseRepository, datasetSchemaProvider, perTurnBindingDetector);
    }

    @Test
    @DisplayName("planChain's request #0 plan carries the suite's own template, bindings, response columns "
            + "and the test case's turn data")
    void planChain_requestZeroCarriesSuiteOwnedFields() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .responseColumns("[]")
                .additionalRequests("[{\"name\":\"second\"}]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder().id(TEST_CASE_ID).data("{}").build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestTemplateDto template = RequestTemplateDto.builder().build();
        List<InputBindingDto> bindings = List.of();
        List<ResponseColumnDefinitionDto> columns = List.of();
        when(jsonbMapper.mapRequestTemplate("{}")).thenReturn(template);
        when(jsonbMapper.mapInputBindings("[]")).thenReturn(bindings);
        when(jsonbMapper.mapResponseColumns("[]")).thenReturn(columns);
        Map<String, Object> data = Map.of("field", "value");
        when(warningsSerializer.deserializeMap("{}")).thenReturn(data);
        when(turnsSerializer.deserializeTurns(null)).thenReturn(null);

        ResolvedRequestService.ChainPlan plan = service.planChain(
                SUITE_ID,
                TEST_CASE_ID,
                List.of(RequestDefinitionDto.builder().name("second").build()));

        ResolvedRequestService.RequestPlan requestZero = plan.requestPlans().getFirst();
        assertThat(requestZero.spec().requestTemplate()).isSameAs(template);
        assertThat(requestZero.spec().inputBindings()).isSameAs(bindings);
        assertThat(requestZero.spec().responseColumns()).isSameAs(columns);
        assertThat(requestZero.turnDataList()).containsExactly(data);
    }
}
