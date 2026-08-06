package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pre-flight validation of a fetched suite's {@code endpointRef}/{@code requestTemplate} before
 * running it against the target deployment.
 *
 * <p>eval-cli only ever receives the target deployment as a CLI parameter — the source suite's config
 * carries no reference to it — so there is no live target-deployment contract to cross-check against
 * (DIAL Core's {@code GET /v1/deployments} exposes only a coarse {@code interfaces} category, not an
 * HTTP method/path/schema). This validates internal consistency of the fetched suite config only,
 * mirroring the checks the source EF's {@code SuiteValidationService.validateDeploymentSuite} performs
 * at suite save time — catching a suite left in an invalid state on the source EF, or drift between
 * fetch time and run time, before it results in a confusing failure mid-run (e.g. a null HTTP method).
 */
@Slf4j
@Component
@LogExecution
public class SuiteContractValidator {

    /**
     * Validates the fetched suite's endpoint/request-template config for every request in the chain —
     * request #0 (the suite's own {@code endpointRef}/{@code requestTemplate}) and each entry of
     * {@code additionalRequests}, which the chain executor invokes with exactly the same requirements.
     *
     * @param suite the suite fetched from the source EF
     * @throws IllegalStateException if any chain request's {@code endpointRef} or {@code requestTemplate}
     *                                is missing a field required to build the request; the message names
     *                                the offending request by its {@code name}, or by its 1-based chain
     *                                index when unlabelled
     */
    public void validate(TestSuiteResponseDto suite) {
        final List<String> errors = new ArrayList<>();
        final EndpointContractDto endpointRef = suite.getEndpointRef();
        final RequestTemplateDto requestTemplate = suite.getRequestTemplate();

        collectRequestErrors(null, endpointRef, requestTemplate, errors);

        final List<RequestDefinitionDto> additionalRequests =
                suite.getAdditionalRequests() != null ? suite.getAdditionalRequests() : List.of();
        for (int i = 0; i < additionalRequests.size(); i++) {
            final RequestDefinitionDto definition = additionalRequests.get(i);
            // 1-based chain index: request #0 is the suite's own endpointRef/requestTemplate,
            // so additionalRequests[i] is chain request i + 1.
            final String label = requestLabel(definition, i + 1);
            if (definition == null) {
                errors.add(label + ": request definition is null");
                continue;
            }
            collectRequestErrors(label, definition.getEndpointRef(), definition.getRequestTemplate(), errors);
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Suite " + suite.getId() + " ('" + suite.getName()
                    + "') failed contract validation: " + String.join("; ", errors));
        }

        warnOnContentTypeMismatch(suite, null, endpointRef, requestTemplate);
        for (int i = 0; i < additionalRequests.size(); i++) {
            final RequestDefinitionDto definition = additionalRequests.get(i);
            warnOnContentTypeMismatch(
                    suite,
                    requestLabel(definition, i + 1),
                    definition.getEndpointRef(),
                    definition.getRequestTemplate());
        }
    }

    /**
     * Applies the request-level checks to one chain entry. {@code label} is {@code null} for request #0
     * (the suite's own config) so its messages stay byte-identical to the pre-multi-request wording;
     * additional requests prefix every message with their label.
     */
    private void collectRequestErrors(
            String label, EndpointContractDto endpointRef, RequestTemplateDto requestTemplate, List<String> errors) {
        final String prefix = label == null ? "" : label + ": ";

        if (endpointRef == null) {
            errors.add(
                    prefix + "endpointRef is missing — required to resolve the HTTP method for the target invocation");
        } else if (endpointRef.getMethod() == null) {
            errors.add(prefix + "endpointRef.method is missing — required for the target invocation");
        }

        if (requestTemplate == null) {
            errors.add(prefix
                    + "requestTemplate is missing — required to build the request sent to the target deployment");
        } else if (requestTemplate.getUrlTemplate() == null
                || requestTemplate.getUrlTemplate().isBlank()) {
            errors.add(prefix + "requestTemplate.urlTemplate is missing or blank");
        }
    }

    /**
     * Identifies an additional request by its author-given {@code name} when set, falling back to its
     * 1-based position in the chain.
     */
    private String requestLabel(RequestDefinitionDto definition, int chainIndex) {
        if (definition != null
                && definition.getName() != null
                && !definition.getName().isBlank()) {
            return "request '" + definition.getName() + "'";
        }
        return "request #" + chainIndex;
    }

    /**
     * Mirrors {@code SuiteValidationService}'s request-body-vs-endpoint-schema content-type
     * cross-check — non-fatal there too (a soft warning, not an HTTP 400), so kept as a log warning
     * here rather than a validation failure.
     */
    private void warnOnContentTypeMismatch(
            TestSuiteResponseDto suite,
            String label,
            EndpointContractDto endpointRef,
            RequestTemplateDto requestTemplate) {
        if (endpointRef == null
                || requestTemplate == null
                || requestTemplate.getBody() == null
                || endpointRef.getRequestBodySchema() == null) {
            return;
        }
        final String bodyContentType = requestTemplate.getBody().getContentType();
        final String schemaContentType = endpointRef.getRequestBodySchema().getContentType();
        if (bodyContentType != null && !bodyContentType.equals(schemaContentType)) {
            log.warn(
                    "Suite {} ('{}'){}: requestTemplate content type '{}' does not match"
                            + " endpointRef.requestBodySchema content type '{}'",
                    suite.getId(),
                    suite.getName(),
                    label == null ? "" : " " + label,
                    bodyContentType,
                    schemaContentType);
        }
    }
}
