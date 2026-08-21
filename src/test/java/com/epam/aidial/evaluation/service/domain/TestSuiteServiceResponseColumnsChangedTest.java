package com.epam.aidial.evaluation.service.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
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
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises {@code TestSuiteService.isResponseColumnsChanged} (private, invoked from {@code update})
 * end-to-end through the public {@code update} API. Unlike {@link TestSuiteServiceTest}, this class
 * wires a real {@link JsonbMapper}/{@link TestSuiteMapper} pair so the JSONB (de)serialization the
 * diff depends on actually runs; every other collaborator stays a plain Mockito mock.
 */
@DisplayName("TestSuiteService.isResponseColumnsChanged Tests")
class TestSuiteServiceResponseColumnsChangedTest {

    private static final UUID SUITE_ID = UUID.randomUUID();

    private TestSuiteRepository testSuiteRepository;
    private TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;
    private SuiteValidationService suiteValidationService;
    private TestSuiteService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonbMapper jsonbMapper = new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper));
        TestSuiteMapper testSuiteMapper = new TestSuiteMapper(jsonbMapper, mock(ValidationWarningsSerializer.class));

        testSuiteRepository = mock(TestSuiteRepository.class);
        testSuiteMetricDefinitionService = mock(TestSuiteMetricDefinitionService.class);
        suiteValidationService = mock(SuiteValidationService.class);
        when(suiteValidationService.validateSuite(any(TestSuiteRequestDto.class), any(), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
        when(testSuiteRepository.save(any(TestSuite.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new TestSuiteService(
                testSuiteRepository,
                mock(DatasetQueryService.class),
                mock(DatasetCascadeService.class),
                mock(DatasetCloneService.class),
                testSuiteMapper,
                jsonbMapper,
                mock(AuthorResolver.class),
                mock(EndpointSchemaRefResolver.class),
                suiteValidationService,
                mock(DatasetSchemaProvider.class),
                mock(RunnableTestCaseSelector.class),
                testSuiteMetricDefinitionService,
                mock(FileService.class),
                mock(Clock.class),
                mock(PlatformTransactionManager.class),
                mock(SortParser.class),
                mock(FilterParser.class),
                mock(ValidationWarningsSerializer.class),
                objectMapper,
                mock(TestSuiteRequestValidator.class),
                new ResponseColumnUnionResolver(jsonbMapper));
    }

    @Test
    @DisplayName("A urlTemplate-only edit to an additional request does not trigger TSMD revalidation")
    void urlTemplateOnlyEditToAdditionalRequest_doesNotRevalidate() {
        TestSuite existing = existingSuiteWithChain(
                List.of(column("configId")), List.of(chainRequest("/v1/old", List.of(column("answer")))));
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(existing));

        TestSuiteRequestDto request = requestWithChain(
                List.of(column("configId")), List.of(chainRequest("/v1/new", List.of(column("answer")))));

        service.update(SUITE_ID, request, null);

        verify(testSuiteMetricDefinitionService, never()).revalidateAllForSuite(eq(SUITE_ID), any(), any());
    }

    @Test
    @DisplayName("Adding a response column to an additional request triggers TSMD revalidation")
    void addingColumnToAdditionalRequest_triggersRevalidation() {
        TestSuite existing = existingSuiteWithChain(
                List.of(column("configId")), List.of(chainRequest("/v1/second", List.of(column("answer")))));
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(existing));

        TestSuiteRequestDto request = requestWithChain(
                List.of(column("configId")),
                List.of(chainRequest("/v1/second", List.of(column("answer"), column("sessionId")))));

        service.update(SUITE_ID, request, null);

        verify(testSuiteMetricDefinitionService, times(1)).revalidateAllForSuite(eq(SUITE_ID), any(), any());
    }

    @Test
    @DisplayName("Renaming an additional request's response column triggers TSMD revalidation")
    void renamingAdditionalRequestColumn_triggersRevalidation() {
        TestSuite existing = existingSuiteWithChain(
                List.of(column("configId")), List.of(chainRequest("/v1/second", List.of(column("answer")))));
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(existing));

        TestSuiteRequestDto request = requestWithChain(
                List.of(column("configId")), List.of(chainRequest("/v1/second", List.of(column("reply")))));

        service.update(SUITE_ID, request, null);

        verify(testSuiteMetricDefinitionService, times(1)).revalidateAllForSuite(eq(SUITE_ID), any(), any());
    }

    @Test
    @DisplayName("An unchanged chain (columns and template alike) does not trigger TSMD revalidation")
    void unchangedChain_doesNotRevalidate() {
        TestSuite existing = existingSuiteWithChain(
                List.of(column("configId")), List.of(chainRequest("/v1/second", List.of(column("answer")))));
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(existing));

        TestSuiteRequestDto request = requestWithChain(
                List.of(column("configId")), List.of(chainRequest("/v1/second", List.of(column("answer")))));

        service.update(SUITE_ID, request, null);

        verify(testSuiteMetricDefinitionService, never()).revalidateAllForSuite(eq(SUITE_ID), any(), any());
    }

    private TestSuite existingSuiteWithChain(
            List<ResponseColumnDefinitionDto> suiteColumns, List<RequestDefinitionDto> additionalRequests) {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonbMapper jsonbMapper = new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper));
        return TestSuite.builder()
                .id(SUITE_ID)
                .name("Suite")
                .suiteType(SuiteType.DEPLOYMENT)
                .version(1L)
                .responseColumns(jsonbMapper.mapResponseColumns(suiteColumns))
                .additionalRequests(jsonbMapper.mapAdditionalRequests(additionalRequests))
                .build();
    }

    private TestSuiteRequestDto requestWithChain(
            List<ResponseColumnDefinitionDto> suiteColumns, List<RequestDefinitionDto> additionalRequests) {
        return TestSuiteRequestDto.builder()
                .name("Suite")
                .suiteType(SuiteType.DEPLOYMENT)
                .responseColumns(suiteColumns)
                .additionalRequests(additionalRequests)
                .build();
    }

    private RequestDefinitionDto chainRequest(String urlTemplate, List<ResponseColumnDefinitionDto> columns) {
        return RequestDefinitionDto.builder()
                .name("second")
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate(urlTemplate).build())
                .responseColumns(columns)
                .build();
    }

    private ResponseColumnDefinitionDto column(String name) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.STRING)
                .expression("usage.total_tokens")
                .build();
    }
}
