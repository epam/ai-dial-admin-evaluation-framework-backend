package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationVersionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.AggregatedMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteMetricDefinitionMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TestSuiteMetricDefinitionServiceTest {

    @Mock
    private TestSuiteMetricDefinitionRepository repository;

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private MetricDeclarationVersionRepository metricDeclarationVersionRepository;

    @Mock
    private TestSuiteMetricDefinitionMapper mapper;

    @Mock
    private SortParser sortParser;

    @Mock
    private FilterParser filterParser;

    @Mock
    private MetricDefinitionValidationService metricDefinitionValidationService;

    @Mock
    private ValidationWarningsSerializer warningsSerializer;

    @Mock
    private JsonbMapper jsonbMapper;

    @Mock
    private ConditionExpressionEvaluator conditionExpressionEvaluator;

    @Mock
    private ResponseColumnUnionResolver responseColumnUnionResolver;

    @InjectMocks
    private TestSuiteMetricDefinitionService service;

    @Test
    void listAggregated_mapsEveryRepositoryResult() {
        UUID testSuiteId = UUID.randomUUID();
        AggregatedMetricDefinition first =
                AggregatedMetricDefinition.builder().id(UUID.randomUUID()).build();
        AggregatedMetricDefinition second =
                AggregatedMetricDefinition.builder().id(UUID.randomUUID()).build();
        AggregatedMetricDefinitionResponseDto firstDto = AggregatedMetricDefinitionResponseDto.builder()
                .id(first.getId())
                .build();
        AggregatedMetricDefinitionResponseDto secondDto = AggregatedMetricDefinitionResponseDto.builder()
                .id(second.getId())
                .build();
        when(repository.findAllAggregatedByTestSuiteId(testSuiteId)).thenReturn(List.of(first, second));
        when(mapper.toAggregatedDto(first)).thenReturn(firstDto);
        when(mapper.toAggregatedDto(second)).thenReturn(secondDto);

        List<AggregatedMetricDefinitionResponseDto> result = service.listAggregated(testSuiteId);

        assertThat(result).containsExactly(firstDto, secondDto);
    }
}
