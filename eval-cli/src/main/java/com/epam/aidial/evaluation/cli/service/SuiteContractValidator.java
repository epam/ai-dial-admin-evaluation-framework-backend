package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
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
     * Validates the fetched suite's endpoint/request-template config.
     *
     * @param suite the suite fetched from the source EF
     * @throws IllegalStateException if {@code endpointRef} or {@code requestTemplate} is missing a
     *                                field required to build the request
     */
    public void validate(TestSuiteResponseDto suite) {
        final List<String> errors = new ArrayList<>();
        final EndpointContractDto endpointRef = suite.getEndpointRef();
        final RequestTemplateDto requestTemplate = suite.getRequestTemplate();

        if (endpointRef == null) {
            errors.add("endpointRef is missing — required to resolve the HTTP method for the target invocation");
        } else if (endpointRef.getMethod() == null) {
            errors.add("endpointRef.method is missing — required for the target invocation");
        }

        if (requestTemplate == null) {
            errors.add("requestTemplate is missing — required to build the request sent to the target deployment");
        } else if (requestTemplate.getUrlTemplate() == null
                || requestTemplate.getUrlTemplate().isBlank()) {
            errors.add("requestTemplate.urlTemplate is missing or blank");
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Suite " + suite.getId() + " ('" + suite.getName()
                    + "') failed contract validation: " + String.join("; ", errors));
        }

        warnOnContentTypeMismatch(suite, endpointRef, requestTemplate);
    }

    /**
     * Mirrors {@code SuiteValidationService}'s request-body-vs-endpoint-schema content-type
     * cross-check — non-fatal there too (a soft warning, not an HTTP 400), so kept as a log warning
     * here rather than a validation failure.
     */
    private void warnOnContentTypeMismatch(
            TestSuiteResponseDto suite, EndpointContractDto endpointRef, RequestTemplateDto requestTemplate) {
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
                    "Suite {} ('{}'): requestTemplate content type '{}' does not match"
                            + " endpointRef.requestBodySchema content type '{}'",
                    suite.getId(),
                    suite.getName(),
                    bodyContentType,
                    schemaContentType);
        }
    }
}
