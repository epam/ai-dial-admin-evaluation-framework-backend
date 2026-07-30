package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.data.db.repository.sql.BulkPatchFields;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkOperationDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkSelectorDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseItemOperationDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseBulkPatchValidator {

    private final TestCaseProperties testCaseProperties;

    public void validate(TestCaseBulkPatchRequestDto request) {
        if (request == null) {
            throw new ValidationException("Request body must not be empty");
        }
        List<TestCaseBulkOperationDto> bulkOperations = nullSafe(request.getBulkOperations());
        List<TestCaseItemOperationDto> itemOperations = nullSafe(request.getItemOperations());

        if (bulkOperations.isEmpty() && itemOperations.isEmpty()) {
            throw new ValidationException("At least one of `bulkOperations` or `itemOperations` must be non-empty");
        }

        TestCaseProperties.Bulk caps = testCaseProperties.getBulk();

        int total = bulkOperations.size() + itemOperations.size();
        if (total > caps.getMaxOperations()) {
            throw new ValidationException("Combined op count " + total + " exceeds maximum " + caps.getMaxOperations());
        }
        if (itemOperations.size() > caps.getMaxItemOperations()) {
            throw new ValidationException(
                    "itemOperations size " + itemOperations.size() + " exceeds maximum " + caps.getMaxItemOperations());
        }

        Set<String> allowedFields = BulkPatchFields.allowedFields();
        for (int i = 0; i < bulkOperations.size(); i++) {
            validateBulkOp(bulkOperations.get(i), i, caps, allowedFields);
        }
        validateItemOps(itemOperations);
    }

    private static void validateBulkOp(
            TestCaseBulkOperationDto op, int index, TestCaseProperties.Bulk caps, Set<String> allowedFields) {
        if (op == null) {
            throw new ValidationException("bulkOperations[" + index + "] must not be null");
        }
        TestCaseBulkSelectorDto selector = op.getSelector();
        if (selector == null) {
            throw new ValidationException("bulkOperations[" + index + "].selector is required");
        }
        boolean hasIds = selector.getIds() != null;
        boolean hasFilter = selector.getFilter() != null;
        if (hasIds == hasFilter) {
            throw new ValidationException(
                    "bulkOperations[" + index + "].selector must declare exactly one of `ids` or `filter`");
        }
        if (hasIds) {
            List<UUID> ids = selector.getIds();
            if (ids.size() > caps.getMaxIdsPerSelector()) {
                throw new ValidationException("bulkOperations[" + index + "].selector.ids size " + ids.size()
                        + " exceeds maximum " + caps.getMaxIdsPerSelector());
            }
            Set<UUID> seen = new HashSet<>();
            for (UUID id : ids) {
                if (id == null) {
                    throw new ValidationException("bulkOperations[" + index + "].selector.ids must not contain null");
                }
                if (!seen.add(id)) {
                    throw new ValidationException(
                            "bulkOperations[" + index + "].selector.ids contains duplicate id: " + id);
                }
            }
        }
        if (op.getPatch() == null || op.getPatch().isEmpty()) {
            throw new ValidationException("bulkOperations[" + index + "].patch must not be empty");
        }
        for (String key : op.getPatch().keySet()) {
            if (!allowedFields.contains(key)) {
                throw new ValidationException("bulkOperations[" + index + "].patch contains non-whitelisted field '"
                        + key + "'. Allowed: " + allowedFields);
            }
        }
    }

    private static void validateItemOps(List<TestCaseItemOperationDto> itemOperations) {
        Set<UUID> seenIds = new HashSet<>();
        for (int i = 0; i < itemOperations.size(); i++) {
            TestCaseItemOperationDto op = itemOperations.get(i);
            if (op == null) {
                throw new ValidationException("itemOperations[" + i + "] must not be null");
            }
            if (op.getId() == null) {
                throw new ValidationException("itemOperations[" + i + "].id is required");
            }
            if (!seenIds.add(op.getId())) {
                throw new ValidationException("itemOperations contains duplicate id: " + op.getId());
            }
            if (op.getPatch() == null || op.getPatch().isEmpty()) {
                throw new ValidationException("itemOperations[" + i + "].patch must not be empty");
            }
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }
}
