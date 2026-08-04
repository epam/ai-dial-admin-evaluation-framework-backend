package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Try-It-Out preview API: resolves a suite's persisted template + bindings against a test case's data.
 * Delegates the actual resolution to the shared module's {@link RequestResolver} (see Decision 3 in the
 * {@code evaluation-runner-core-module} change's {@code design.md}) — this class owns only the DB-backed
 * lookup of the suite/test-case entities.
 */
@Service
@LogExecution
@RequiredArgsConstructor
public class ResolvedRequestService {

    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseRepository testCaseRepository;
    private final JsonbMapper jsonbMapper;
    private final ValidationWarningsSerializer warningsSerializer;
    private final RequestResolver requestResolver;

    /**
     * Delegates to {@link #resolveRequest(UUID, UUID, int)} previewing request #0 — the suite's own
     * request — so the pre-existing {@code TryItOutService} call site (which never needs to preview a
     * chained request) stays untouched.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public ResolvedRequestDto resolveRequest(UUID testSuiteId, UUID testCaseId) {
        return resolveRequest(testSuiteId, testCaseId, 0);
    }

    /**
     * Previews the resolved request for one request in the suite's chain: {@code requestIndex == 0}
     * selects the suite's own {@code requestTemplate}/{@code inputBindings}; {@code requestIndex > 0}
     * selects {@code additionalRequests[requestIndex - 1]}. Out of range (negative, or greater than
     * {@code additionalRequests.size()}) is rejected with a {@link ValidationException} (HTTP 400 per
     * design D19). Resolution always uses an <strong>empty</strong> JSONata frame — no chain is executed
     * to populate prior requests' response columns, so a chained request's references to earlier
     * columns resolve as JSONata undefined and surface as validation warnings, exactly like any other
     * unresolved placeholder.
     */
    @Transactional(value = "metaTransactionManager", readOnly = true)
    public ResolvedRequestDto resolveRequest(UUID testSuiteId, UUID testCaseId, int requestIndex) {
        TestSuite suite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));
        // Test cases now live on the dataset; resolve via the suite's datasetId so the suite-scoped
        // route still returns the test case the caller meant, while the underlying lookup is dataset-rooted.
        TestCase tc = testCaseRepository
                .findByIdAndDatasetId(testCaseId, suite.getDatasetId())
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + testCaseId));

        RequestTemplateDto template;
        List<InputBindingDto> bindings;
        if (requestIndex == 0) {
            // Template + bindings are suite-owned now (overrides have been dropped per the dataset refactor).
            template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
            bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());
        } else {
            RequestDefinitionDto additionalRequest = resolveAdditionalRequest(suite, requestIndex);
            template = additionalRequest.getRequestTemplate();
            bindings = additionalRequest.getInputBindings();
        }

        Map<String, Object> data = warningsSerializer.deserializeMap(tc.getData());

        return requestResolver.resolve(template, bindings, data);
    }

    private RequestDefinitionDto resolveAdditionalRequest(TestSuite suite, int requestIndex) {
        List<RequestDefinitionDto> additionalRequests =
                jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests());
        int additionalIndex = requestIndex - 1;
        if (requestIndex < 0 || additionalRequests == null || additionalIndex >= additionalRequests.size()) {
            throw new ValidationException("requestIndex " + requestIndex
                    + " is out of range for suite " + suite.getId() + " (chain length "
                    + (additionalRequests == null ? 0 : additionalRequests.size() + 1) + ")");
        }
        return additionalRequests.get(additionalIndex);
    }
}
