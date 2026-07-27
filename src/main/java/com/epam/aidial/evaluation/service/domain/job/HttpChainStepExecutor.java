package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import com.epam.aidial.evaluation.service.domain.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.service.domain.RequestSpec;
import com.epam.aidial.evaluation.service.domain.ResolutionScope;
import com.epam.aidial.evaluation.service.domain.ResolvedRequestService;
import com.epam.aidial.evaluation.service.domain.ResponseColumnExtractor;
import com.epam.aidial.evaluation.service.domain.SerializedBody;
import com.epam.aidial.evaluation.service.domain.TemplateVariableExtractor;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/**
 * Executes one HTTP request of a multi-request chain: resolves that request's own template and bindings
 * (including {@code responseField} bindings against the accumulated column map), issues the call with that
 * request's own {@code endpointRef} method and resolved URL against the suite-level deployment, and extracts
 * that request's own response columns.
 *
 * <p>There is deliberately no message-history threading between chain requests: "chain" means data flow, not
 * conversation. Each request's body is resolved independently from its own template. Conversational
 * accumulation is what multi-turn test cases already provide, and combining the two is excluded at run
 * creation.
 *
 * <p>Retries, backoff, cancellation checks, the per-call rate-limit gate, and oversize-body handling are
 * delegated to {@link DeploymentTurnInvoker}, which is why the rate limiter needs no fourth call site.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class HttpChainStepExecutor implements ChainStepExecutor {

    private final ResolvedRequestService resolvedRequestService;
    private final TemplateVariableExtractor templateVariableExtractor;
    private final DialCoreUrlBuilder urlBuilder;
    private final RequestBodySerializerRegistry serializerRegistry;
    private final ResponseColumnExtractor responseColumnExtractor;
    private final EvaluationRunProperties evaluationRunProperties;
    private final DeploymentTurnInvoker deploymentTurnInvoker;
    private final QuietJsonService jsonService;

    @Override
    public ChainRequestType supportedType() {
        return ChainRequestType.HTTP;
    }

    @Override
    public ChainStepOutcome execute(ChainStepRequest step) {
        final RequestSpec request = step.request();
        final EvaluationContext context = step.context();

        // Write-time validation guarantees a responseField names a column some EARLIER request declares, but
        // it cannot guarantee runtime resolution: a request can return 200 while a column's JSONata matches
        // nothing. Detect that here, before sending, so the chain fails fast instead of firing a request built
        // from a missing dependency. A placeholder-declared default makes continuation opt-in and visible.
        final List<String> missing = findUnresolvableResponseFields(request, step.responseValues());
        if (!missing.isEmpty()) {
            log.warn(
                    "Chain request {} ('{}') cannot resolve response column(s) {} and declares no placeholder "
                            + "default; failing this test case",
                    request.index(),
                    request.label(),
                    missing);
            return ChainStepOutcome.unresolvedDependency(null, missing);
        }

        final ResolvedRequestDto resolved = resolvedRequestService.resolveInScope(
                request.requestTemplate(),
                request.safeInputBindings(),
                ResolutionScope.of(step.testCaseData(), step.responseValues()));

        final ResolvedBodyDto resolvedBody = resolved.getBody();
        final String requestBodyJson = DeploymentInvocationSupport.serializeBodyForAnalytics(resolvedBody, jsonService);

        final String deploymentId = context.getSnapshotDeploymentRef() != null
                ? context.getSnapshotDeploymentRef().getId()
                : null;
        final String path = urlBuilder.buildUrl(deploymentId, resolved.getUrl());
        final HttpMethod method =
                request.endpointRef() != null ? request.endpointRef().getMethod() : null;

        final HttpHeaders headers = DeploymentInvocationSupport.buildHeaders(
                resolved.getHeaders(), evaluationRunProperties.getExecution().getHeaderBlacklist());
        final MultiValueMap<String, String> queryParams =
                DeploymentInvocationSupport.buildQueryParams(resolved.getQueryParams());

        Object body = null;
        if (resolvedBody != null) {
            final SerializedBody serialized = serializerRegistry.serialize(resolvedBody);
            // Skip Content-Type for multipart — RestClient generates the boundary itself.
            if (!MediaType.MULTIPART_FORM_DATA.equals(serialized.contentType())) {
                headers.setContentType(serialized.contentType());
            }
            body = serialized.body();
        }

        final TurnOutcome outcome = deploymentTurnInvoker.invoke(context, method, path, headers, queryParams, body);

        if (outcome.status() != ExecutionStatus.SUCCESS) {
            return ChainStepOutcome.failed(
                    outcome.status(),
                    outcome.statusCode(),
                    requestBodyJson,
                    outcome.responseBody(),
                    outcome.retryCount());
        }

        // Extraction is scoped to THIS request's own response columns, so the row's extracted_columns holds
        // only what this request produced — not the accumulated set.
        final ResponseColumnExtractor.ExtractionResult extraction =
                responseColumnExtractor.extract(request.safeResponseColumns(), outcome.responseBody());

        return new ChainStepOutcome(
                ExecutionStatus.SUCCESS,
                outcome.statusCode(),
                requestBodyJson,
                outcome.responseBody(),
                outcome.retryCount(),
                extraction.extractedColumns(),
                extraction.extractionWarnings(),
                toValueMap(extraction.extractedColumns()),
                List.of());
    }

    /**
     * Response columns this request binds to via {@code responseField} that are absent from the accumulated
     * map AND whose placeholder declares no default. A declared default is honored by
     * {@code TemplateVariableResolver}, so those are not reported here.
     */
    private List<String> findUnresolvableResponseFields(RequestSpec request, Map<String, Object> responseValues) {
        final List<InputBindingDto> chainBindings = request.safeInputBindings().stream()
                .filter(b -> b != null
                        && b.getResponseField() != null
                        && !b.getResponseField().isBlank())
                .toList();
        if (chainBindings.isEmpty()) {
            return List.of();
        }
        final Set<String> variablesWithDefault =
                templateVariableExtractor.extractWithWarnings(request.requestTemplate()).getVariables().stream()
                        .filter(TemplateVariableExtractor.ExtractedVariable::isHasDefault)
                        .map(TemplateVariableExtractor.ExtractedVariable::getName)
                        .collect(Collectors.toSet());

        final Set<String> missing = new HashSet<>();
        for (InputBindingDto binding : chainBindings) {
            if (responseValues.containsKey(binding.getResponseField())) {
                continue;
            }
            if (variablesWithDefault.contains(binding.getTemplateVariable())) {
                continue;
            }
            missing.add(binding.getResponseField());
        }
        return List.copyOf(missing);
    }

    /** Parses this request's extracted columns back into a typed map for merging into the accumulator. */
    private Map<String, Object> toValueMap(String extractedColumnsJson) {
        if (extractedColumnsJson == null || extractedColumnsJson.isBlank()) {
            return Map.of();
        }
        return jsonService.readMapOrEmpty(extractedColumnsJson);
    }
}
