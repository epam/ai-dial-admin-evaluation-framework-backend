package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.suite.HttpSuite;
import com.epam.aidial.evaluation.runner.model.suite.McpSuite;
import com.epam.aidial.evaluation.runner.model.suite.SuiteDefinition;
import com.epam.aidial.evaluation.runner.model.suite.SuiteDefinitions;
import com.epam.aidial.evaluation.runner.model.target.HttpTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.model.target.SuiteTarget;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Derives a {@link SuiteDefinition} (target + invocation shape) from the flat suite entity or request DTO. */
@Component
@LogExecution
@RequiredArgsConstructor
public class SuiteDefinitionResolver {

    private final SuiteTargetResolver suiteTargetResolver;
    private final JsonbMapper jsonbMapper;

    /** Derives the definition from the persisted entity, whose structured fields are JSONB strings. */
    public SuiteDefinition fromEntity(TestSuite suite) {
        final SuiteTarget target = suiteTargetResolver.fromEntity(suite);
        return switch (target) {
            case HttpTarget h ->
                new HttpSuite(
                        h,
                        SuiteDefinitions.httpChain(
                                suite.getRequestName(),
                                jsonbMapper.mapEndpointContract(suite.getEndpointRef()),
                                jsonbMapper.mapRequestTemplate(suite.getRequestTemplate()),
                                jsonbMapper.mapInputBindings(suite.getInputBindings()),
                                jsonbMapper.mapResponseColumns(suite.getResponseColumns()),
                                jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests())));
            case McpToolTarget m ->
                new McpSuite(
                        m,
                        jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate()),
                        jsonbMapper.mapInputBindings(suite.getInputBindings()),
                        jsonbMapper.mapResponseColumns(suite.getResponseColumns()));
        };
    }

    /**
     * Derives the definition from the request DTO. The target derivation enforces the 400 validation
     * ({@link SuiteTargetResolver#fromRequest}); DTO values are passed through as-is, so consumers keep their
     * own null handling.
     * Request #0 values pass through as given; additional requests' null bindings/columns are normalized to
     * empty exactly like the entity/snapshot paths (BindingValidator is null-safe, so validation is unchanged).
     */
    public SuiteDefinition fromRequest(TestSuiteRequestDto dto) {
        final SuiteTarget target = suiteTargetResolver.fromRequest(dto);
        return switch (target) {
            case HttpTarget h ->
                new HttpSuite(
                        h,
                        SuiteDefinitions.httpChain(
                                dto.getRequestName(),
                                dto.getEndpointRef(),
                                dto.getRequestTemplate(),
                                dto.getInputBindings(),
                                dto.getResponseColumns(),
                                dto.getAdditionalRequests()));
            case McpToolTarget m ->
                new McpSuite(m, dto.getArgumentTemplate(), dto.getInputBindings(), dto.getResponseColumns());
        };
    }
}
