package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkSelectorDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseBulkSelectorResolver {

    private final TestCaseRepository testCaseRepository;
    private final FilterParser filterParser;
    private final TestCaseProperties testCaseProperties;

    public List<UUID> resolve(UUID datasetId, TestCaseBulkSelectorDto selector) {
        int cap = testCaseProperties.getBulk().getMaxIdsPerSelector();
        if (selector.getIds() != null) {
            return resolveIds(datasetId, selector.getIds());
        }
        return resolveFilter(datasetId, selector.getFilter(), cap);
    }

    private List<UUID> resolveIds(UUID datasetId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> existing = testCaseRepository.findExistingIdsInDataset(datasetId, ids);
        if (existing.size() != ids.size()) {
            Set<UUID> existingSet = new HashSet<>(existing);
            UUID missing = ids.stream()
                    .filter(id -> !existingSet.contains(id))
                    .findFirst()
                    .orElse(null);
            throw new EntityNotFoundException("TestCase not found in dataset " + datasetId + ": " + missing);
        }
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    private List<UUID> resolveFilter(UUID datasetId, List<String> filterParams, int cap) {
        try {
            List<FilterCondition> filters = filterParser.parse(filterParams != null ? filterParams : List.of());
            List<UUID> ids = testCaseRepository.findIdsByDatasetIdAndFilter(datasetId, filters, cap + 1);
            if (ids.size() > cap) {
                throw new ValidationException("Filter selector matched more than " + cap + " test cases (cap is "
                        + "test-case.bulk.max-ids-per-selector)");
            }
            return ids;
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }
    }
}
