package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationVersionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.dto.AggregatedMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteMetricDefinitionMapper;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class TestSuiteMetricDefinitionService {

    private final TestSuiteMetricDefinitionRepository repository;
    private final TestSuiteRepository testSuiteRepository;
    private final DatasetRepository datasetRepository;
    private final MetricDeclarationVersionRepository metricDeclarationVersionRepository;
    private final TestSuiteMetricDefinitionMapper mapper;
    private final SortParser sortParser;
    private final FilterParser filterParser;
    private final MetricDefinitionValidationService metricDefinitionValidationService;
    private final ValidationWarningsSerializer warningsSerializer;
    private final JsonbMapper jsonbMapper;
    private final ConditionExpressionEvaluator conditionExpressionEvaluator;

    @Transactional("metaTransactionManager")
    public TestSuiteMetricDefinitionResponseDto create(UUID testSuiteId, TestSuiteMetricDefinitionRequestDto dto) {
        log.info("Creating TSMD '{}' for test suite: {}", dto.getName(), testSuiteId);

        TestSuite suite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("Test suite not found with id: " + testSuiteId));

        MetricDeclarationVersion version = metricDeclarationVersionRepository
                .findByIdAndMetricDeclarationId(dto.getMetricDeclarationVersionId(), dto.getMetricDeclarationId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Metric declaration version not found with id: " + dto.getMetricDeclarationVersionId()
                                + " for metric declaration: " + dto.getMetricDeclarationId()));

        checkNoDuplicateProperties(dto.getConfigBindings(), "configBindings");
        checkNoDuplicateProperties(dto.getInputBindings(), "inputBindings");
        conditionExpressionEvaluator.validate(dto.getCondition());

        String testCaseSchema = loadDatasetSchema(suite);
        ValidationResult result = metricDefinitionValidationService.validate(
                dto.getConfigBindings(),
                dto.getInputBindings(),
                version.getConfigSchema(),
                version.getInputSchema(),
                testCaseSchema,
                suite.getResponseColumns(),
                version.getOutputSchema());

        TestSuiteMetricDefinition entity = mapper.toEntity(dto, testSuiteId);
        entity.setValid(result.isValid());
        entity.setValidationWarnings(warningsSerializer.serializeWarnings(result.getWarnings()));

        try {
            TestSuiteMetricDefinition saved = repository.save(entity);
            TestSuiteMetricDefinition fetched = repository
                    .findByIdAndTestSuiteId(saved.getId(), testSuiteId)
                    .orElseThrow();
            return mapper.toDto(fetched);
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex,
                    "A metric definition with name '" + dto.getName() + "' already exists in this test suite",
                    dto.getName());
            throw ex;
        }
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public TestSuiteMetricDefinitionResponseDto getById(UUID testSuiteId, UUID id) {
        log.debug("Fetching TSMD by id: {} for suite: {}", id, testSuiteId);
        return repository
                .findByIdAndTestSuiteId(id, testSuiteId)
                .map(mapper::toDto)
                .orElseThrow(() -> new EntityNotFoundException("Metric definition not found with id: " + id));
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public AggregatedMetricDefinitionResponseDto getAggregatedById(UUID testSuiteId, UUID id) {
        log.debug("Fetching aggregated TSMD by id: {} for suite: {}", id, testSuiteId);
        return repository
                .findAggregatedByIdAndTestSuiteId(id, testSuiteId)
                .map(mapper::toAggregatedDto)
                .orElseThrow(() -> new EntityNotFoundException("Metric definition not found with id: " + id));
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<TestSuiteMetricDefinitionResponseDto> list(
            UUID testSuiteId, int page, int size, List<String> sort, List<String> filter, boolean includeTotalCount) {
        log.debug("Listing TSMDs for suite: {}, page={}, size={}", testSuiteId, page, size);
        PageRequest pageRequest = PageRequest.builder()
                .page(page)
                .size(size)
                .sort(sortParser.parse(sort != null ? sort : List.of()))
                .build();
        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());

        try {
            Page<TestSuiteMetricDefinition> resultPage =
                    repository.findAll(testSuiteId, pageRequest, filters, includeTotalCount);
            return PageResponseDto.from(resultPage, mapper::toDto, includeTotalCount);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }
    }

    @Transactional("metaTransactionManager")
    public TestSuiteMetricDefinitionResponseDto update(
            UUID testSuiteId, UUID id, TestSuiteMetricDefinitionRequestDto dto) {
        log.info("Updating TSMD with id: {} for suite: {}", id, testSuiteId);

        TestSuite suite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("Test suite not found with id: " + testSuiteId));

        TestSuiteMetricDefinition existing = repository
                .findByIdAndTestSuiteId(id, testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("Metric definition not found with id: " + id));

        MetricDeclarationVersion version = metricDeclarationVersionRepository
                .findByIdAndMetricDeclarationId(dto.getMetricDeclarationVersionId(), dto.getMetricDeclarationId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Metric declaration version not found with id: " + dto.getMetricDeclarationVersionId()
                                + " for metric declaration: " + dto.getMetricDeclarationId()));

        checkNoDuplicateProperties(dto.getConfigBindings(), "configBindings");
        checkNoDuplicateProperties(dto.getInputBindings(), "inputBindings");
        conditionExpressionEvaluator.validate(dto.getCondition());

        String testCaseSchema = loadDatasetSchema(suite);
        ValidationResult result = metricDefinitionValidationService.validate(
                dto.getConfigBindings(),
                dto.getInputBindings(),
                version.getConfigSchema(),
                version.getInputSchema(),
                testCaseSchema,
                suite.getResponseColumns(),
                version.getOutputSchema());

        mapper.update(existing, dto);
        existing.setValid(result.isValid());
        existing.setValidationWarnings(warningsSerializer.serializeWarnings(result.getWarnings()));

        try {
            repository.update(existing);
            TestSuiteMetricDefinition fetched =
                    repository.findByIdAndTestSuiteId(id, testSuiteId).orElseThrow();
            return mapper.toDto(fetched);
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex,
                    "A metric definition with name '" + dto.getName() + "' already exists in this test suite",
                    dto.getName());
            throw ex;
        }
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public List<AggregatedMetricDefinition> findAllAggregatedByTestSuiteId(UUID testSuiteId) {
        return repository.findAllAggregatedByTestSuiteId(testSuiteId);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public List<AggregatedMetricDefinition> findAllEnabledAndValidAggregatedByTestSuiteId(UUID testSuiteId) {
        return repository.findAllEnabledAndValidAggregatedByTestSuiteId(testSuiteId);
    }

    /**
     * Revalidates all TSMDs for a suite using current suite schema and response columns.
     * Called synchronously from suite update (when schema changes) and from manual revalidation endpoint.
     */
    @Transactional("metaTransactionManager")
    public void revalidateAllForSuite(UUID suiteId, String testCaseSchemaJson, String responseColumnsJson) {
        log.info("Revalidating all TSMDs for suite: {}", suiteId);
        List<AggregatedMetricDefinition> tsmds = repository.findAllAggregatedByTestSuiteId(suiteId);
        for (AggregatedMetricDefinition tsmd : tsmds) {
            List<MetricParameterBindingDto> configBindings = parseBindings(tsmd.getConfigBindings(), tsmd.getId());
            List<MetricParameterBindingDto> inputBindings = parseBindings(tsmd.getInputBindings(), tsmd.getId());
            ValidationResult result = metricDefinitionValidationService.validate(
                    configBindings,
                    inputBindings,
                    tsmd.getVersionConfigSchema(),
                    tsmd.getVersionInputSchema(),
                    testCaseSchemaJson,
                    responseColumnsJson,
                    tsmd.getVersionOutputSchema());
            repository.updateValidation(
                    tsmd.getId(), result.isValid(), warningsSerializer.serializeWarnings(result.getWarnings()));
        }
        log.info("Revalidated {} TSMDs for suite: {}", tsmds.size(), suiteId);
    }

    @Transactional("metaTransactionManager")
    public void delete(UUID testSuiteId, UUID id) {
        log.info("Deleting TSMD with id: {} for suite: {}", id, testSuiteId);

        repository
                .findByIdAndTestSuiteId(id, testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("Metric definition not found with id: " + id));

        repository.deleteById(id);
    }

    private void checkNoDuplicateProperties(List<MetricParameterBindingDto> bindings, String fieldName) {
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (MetricParameterBindingDto binding : bindings) {
            if (binding != null && binding.getProperty() != null && !seen.add(binding.getProperty())) {
                throw new ValidationException("Duplicate property '" + binding.getProperty() + "' in " + fieldName);
            }
        }
    }

    private List<MetricParameterBindingDto> parseBindings(String bindingsJson, UUID tsmdId) {
        if (bindingsJson == null || bindingsJson.isBlank()) {
            return List.of();
        }
        try {
            return jsonbMapper.mapMetricBindings(bindingsJson);
        } catch (Exception e) {
            log.warn("Failed to parse bindings for TSMD {}: {}", tsmdId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Loads the raw JSONB string of the dataset's testCaseSchema for the suite's referenced dataset.
     * The schema lives on the dataset (not the suite) under the dataset-rooted model; TSMD validation
     * still consumes the raw String form (the underlying validation service parses it itself).
     */
    private String loadDatasetSchema(TestSuite suite) {
        Dataset dataset = datasetRepository
                .findById(suite.getDatasetId())
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found with id: " + suite.getDatasetId()
                        + " (referenced by suite " + suite.getId() + ")"));
        return dataset.getTestCaseSchema();
    }
}
