package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkDeleteRequestDto;
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
public class TestCaseBulkDeleteValidator {

    private final TestCaseProperties testCaseProperties;

    public void validate(TestCaseBulkDeleteRequestDto request) {
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            throw new ValidationException("ids must not be empty");
        }
        List<UUID> ids = request.getIds();
        int max = testCaseProperties.getBulk().getMaxDeleteIds();
        if (ids.size() > max) {
            throw new ValidationException("ids size " + ids.size() + " exceeds maximum " + max);
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            if (id == null) {
                throw new ValidationException("ids[" + i + "] must not be null");
            }
            if (!seen.add(id)) {
                throw new ValidationException("ids contains duplicate id: " + id);
            }
        }
    }
}
