package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteProperties;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hard (reject-with-400) validation of a suite's normalized chain, applied at suite save.
 *
 * <p>These rules reject rather than mark the suite {@code isValid = false} because a suite save is one
 * small form submission with no partial-success semantics to preserve — unlike the multi-turn turn cap,
 * which invalidates because it arrives via bulk CSV import where failing a 10,000-row file over one bad
 * row is disruptive. Persisting an over-cap or self-contradictory chain would also leave an unrunnable
 * suite looking valid.
 *
 * <p>Per-element template-versus-{@code endpointRef} checks are deliberately NOT here: those are soft,
 * contributing {@code validationWarnings} and {@code isValid} through {@code SuiteValidationService}.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ChainConfigurationValidator {

    private final TestSuiteProperties testSuiteProperties;

    /**
     * Validates the whole chain, throwing {@link ValidationException} (→ HTTP 400 {@code VALIDATION_ERROR})
     * on the first violated rule. A single-request chain is subject to the same rules, which is how a
     * stray {@code responseField} on a single-request suite is caught.
     */
    public void validate(List<RequestSpec> chain) {
        if (chain == null || chain.isEmpty()) {
            return;
        }
        validateChainLength(chain);
        validateNoMcpElements(chain);
        validateUniqueLabels(chain);
        validateUniqueResponseColumns(chain);
        validateResponseFieldReferences(chain);
    }

    /**
     * The configured cap. Re-checked at run creation because a configurable cap can be lowered after a
     * suite is persisted.
     */
    public int maxRequests() {
        return testSuiteProperties.getMultiRequest().getMaxRequests();
    }

    private void validateChainLength(List<RequestSpec> chain) {
        final int cap = maxRequests();
        if (chain.size() > cap) {
            throw new ValidationException("Suite chain has " + chain.size()
                    + " requests, exceeding the configured maximum of " + cap
                    + " (test-suite.multi-request.max-requests)");
        }
    }

    private void validateNoMcpElements(List<RequestSpec> chain) {
        for (RequestSpec request : chain) {
            if (request.type() == ChainRequestType.MCP_TOOL) {
                throw new ValidationException("Chain request " + request.index() + " ('" + request.label()
                        + "') declares type MCP_TOOL, but MCP chaining is not supported. "
                        + "Use type HTTP, or configure a single-request MCP_TOOL suite instead.");
            }
        }
    }

    /**
     * Uniqueness is checked on the <b>resolved</b> label set — after {@code request-{n}} defaulting — so a
     * single check catches both duplicate explicit labels and an explicit label colliding with another
     * request's default (e.g. request 3 labelled {@code "request-2"} while request 2 has no label).
     */
    private void validateUniqueLabels(List<RequestSpec> chain) {
        final Set<String> seen = new HashSet<>();
        for (RequestSpec request : chain) {
            if (!seen.add(request.label())) {
                throw new ValidationException("Duplicate request label '" + request.label()
                        + "' in the suite chain at request " + request.index()
                        + ". Labels must be unique after defaulting absent labels to 'request-{n}'.");
            }
        }
    }

    /**
     * Response column names must be unique across the <b>whole chain</b>, not merely within one request.
     * Chain-wide uniqueness is what lets every downstream consumer — metric bindings, result-row
     * {@code extractedColumns} keys, CSV export headers, query-DSL field names — keep referencing columns
     * by bare name with no request qualification.
     */
    private void validateUniqueResponseColumns(List<RequestSpec> chain) {
        final Set<String> seen = new HashSet<>();
        for (RequestSpec request : chain) {
            for (ResponseColumnDefinitionDto column : request.safeResponseColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                if (!seen.add(column.getName())) {
                    throw new ValidationException("Duplicate response column name '" + column.getName()
                            + "' declared at chain request " + request.index() + " ('" + request.label()
                            + "'). Response column names must be unique across the whole chain.");
                }
            }
        }
    }

    /**
     * A {@code responseField} must name a column declared by a <b>strictly earlier</b> request. Forward and
     * self references are rejected because sequential execution can never satisfy them: the referenced
     * column does not exist in the accumulated map at the moment the referencing request is resolved.
     */
    private void validateResponseFieldReferences(List<RequestSpec> chain) {
        final Set<String> declaredSoFar = new LinkedHashSet<>();
        for (RequestSpec request : chain) {
            for (InputBindingDto binding : request.safeInputBindings()) {
                if (binding == null
                        || binding.getResponseField() == null
                        || binding.getResponseField().isBlank()) {
                    continue;
                }
                final String referenced = binding.getResponseField();
                if (declaredSoFar.contains(referenced)) {
                    continue;
                }
                throw new ValidationException(buildBadReferenceMessage(chain, request, binding, referenced));
            }
            // Only add AFTER validating this request's own bindings, so a self-reference is rejected.
            for (ResponseColumnDefinitionDto column : request.safeResponseColumns()) {
                if (column != null && column.getName() != null) {
                    declaredSoFar.add(column.getName());
                }
            }
        }
    }

    private static String buildBadReferenceMessage(
            List<RequestSpec> chain, RequestSpec request, InputBindingDto binding, String referenced) {
        final String prefix = "Binding for template variable '" + binding.getTemplateVariable()
                + "' at chain request " + request.index() + " ('" + request.label()
                + "') references responseField '" + referenced + "', ";
        if (chain.size() == 1) {
            return prefix + "but the suite is single-request — a responseField requires an earlier chain "
                    + "request to produce the column.";
        }
        if (declaresColumn(request, referenced)) {
            return prefix + "which that same request declares. A responseField must reference a STRICTLY "
                    + "EARLIER request's response column.";
        }
        if (declaredLaterThan(chain, request.index(), referenced)) {
            return prefix + "which is declared by a LATER chain request. Sequential execution cannot satisfy "
                    + "a forward reference.";
        }
        return prefix + "which no request in the chain declares.";
    }

    private static boolean declaresColumn(RequestSpec request, String columnName) {
        return request.safeResponseColumns().stream()
                .anyMatch(column -> column != null && columnName.equals(column.getName()));
    }

    private static boolean declaredLaterThan(List<RequestSpec> chain, int index, String columnName) {
        return chain.stream()
                .filter(request -> request.index() > index)
                .anyMatch(request -> declaresColumn(request, columnName));
    }
}
