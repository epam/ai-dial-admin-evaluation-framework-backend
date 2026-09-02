package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationRepository;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationVersionRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationVersionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseMapper;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.MetricDeclarationMapper;
import com.epam.aidial.evaluation.service.domain.mapper.MetricDeclarationVersionMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class MetricDeclarationService {

    private final MetricDeclarationRepository metricDeclarationRepository;
    private final MetricDeclarationVersionRepository metricDeclarationVersionRepository;
    private final MetricDeclarationMapper metricDeclarationMapper;
    private final MetricDeclarationVersionMapper metricDeclarationVersionMapper;
    private final SortParser sortParser;
    private final FilterParser filterParser;

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<MetricDeclarationResponseDto> getAll(
            int page, int size, List<String> sort, List<String> filter, boolean includeTotalCount) {
        PageRequest pageRequest = PageRequest.builder()
                .page(page)
                .size(size)
                .sort(sortParser.parse(sort != null ? sort : List.of()))
                .build();
        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        return getAll(pageRequest, filters, includeTotalCount);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<MetricDeclarationResponseDto> getAll(
            PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        log.debug(
                "Fetching all MetricDeclarations with pagination: page={}, size={}",
                pageRequest.getPage(),
                pageRequest.getSize());
        List<FilterCondition> safeFilters = filters != null ? filters : List.of();
        try {
            Page<MetricDeclaration> pageResult =
                    metricDeclarationRepository.findAll(pageRequest, safeFilters, includeTotalCount);
            return PageResponseMapper.from(pageResult, metricDeclarationMapper::toDto, includeTotalCount);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public MetricDeclarationResponseDto getById(UUID id) {
        log.debug("Fetching MetricDeclaration by id: {}", id);
        return metricDeclarationRepository
                .findById(id)
                .map(metricDeclarationMapper::toDto)
                .orElseThrow(() -> new EntityNotFoundException("MetricDeclaration not found with id: " + id));
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public MetricDeclarationVersionResponseDto getLatestVersion(UUID declarationId) {
        log.debug("Fetching latest MetricDeclarationVersion for declaration id: {}", declarationId);
        if (metricDeclarationRepository.findById(declarationId).isEmpty()) {
            throw new EntityNotFoundException("MetricDeclaration not found with id: " + declarationId);
        }
        return metricDeclarationVersionRepository
                .findLatestByMetricDeclarationId(declarationId)
                .map(metricDeclarationVersionMapper::toDto)
                .orElseThrow(() ->
                        new EntityNotFoundException("No version found for metric declaration id: " + declarationId));
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public List<MetricDeclarationVersionResponseDto> getLatestVersions() {
        log.debug("Fetching the latest MetricDeclarationVersion of every metric declaration");
        return metricDeclarationVersionRepository.findLatestPerMetricDeclaration().stream()
                .map(metricDeclarationVersionMapper::toDto)
                .toList();
    }
}
