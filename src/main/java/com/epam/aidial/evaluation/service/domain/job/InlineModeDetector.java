package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.constants.JsonataReservedNames;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Derives whether a run is <b>inline</b> (see the {@code inline-metric-evaluation} change's
 * {@code design.md} Decision 1): a run is inline iff {@code "$_metrics"} appears as a substring in any
 * request-template JSON body (root {@code requestTemplate} or any {@code additionalRequests[i]
 * .requestTemplate}, {@code content} or {@code jsonataContent}) or in any enabled+valid TSMD's
 * {@code configBindings}/{@code inputBindings} raw JSON. A {@code suiteType = MCP_TOOL} run is always
 * non-inline. Only {@link JsonRequestBodyDto} bodies are scanned — multipart/URL-encoded bodies, URLs,
 * headers and query parameters cannot bind {@code $_metrics} at all, so they are never scanned.
 *
 * <p>Inputs are the run's frozen {@link SuiteSnapshotDto} (what Phase 1 actually executes) and the
 * live enabled+valid TSMD list (what Phase 2 would otherwise evaluate) — never the live suite, so a
 * suite edited between run creation and dispatch cannot silently change an in-flight run's mode.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class InlineModeDetector {

    private static final String METRICS_TOKEN = "$" + JsonataReservedNames.METRICS_FRAME_BINDING;

    private final ObjectMapper objectMapper;

    /**
     * Returns {@code true} iff {@code snapshot} or {@code tsmds} references {@code $_metrics} on a
     * scannable surface, per the class Javadoc. A false positive (the token is present but unreachable)
     * is deliberately still treated as inline — no JSONata AST walk is performed.
     */
    public boolean isInline(SuiteSnapshotDto snapshot, List<AggregatedMetricDefinition> tsmds) {
        SuiteType suiteType =
                snapshot.getSuiteType() != null ? SuiteType.valueOf(snapshot.getSuiteType()) : SuiteType.DEPLOYMENT;
        if (suiteType == SuiteType.MCP_TOOL) {
            return false;
        }

        if (requestTemplateReferencesMetrics(snapshot.getRequestTemplate())) {
            return true;
        }

        List<RequestDefinitionDto> additionalRequests = snapshot.getAdditionalRequests();
        if (additionalRequests != null) {
            for (RequestDefinitionDto request : additionalRequests) {
                if (request != null && requestTemplateReferencesMetrics(request.getRequestTemplate())) {
                    return true;
                }
            }
        }

        for (AggregatedMetricDefinition tsmd : tsmds) {
            if (containsMetricsToken(tsmd.getConfigBindings()) || containsMetricsToken(tsmd.getInputBindings())) {
                return true;
            }
        }

        return false;
    }

    private boolean requestTemplateReferencesMetrics(RequestTemplateDto template) {
        if (template == null || !(template.getBody() instanceof JsonRequestBodyDto jsonBody)) {
            return false;
        }
        return containsMetricsToken(jsonBody.getJsonataContent()) || containsMetricsToken(serializeContent(jsonBody));
    }

    private String serializeContent(JsonRequestBodyDto jsonBody) {
        Map<String, Object> content = jsonBody.getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JacksonException e) {
            log.warn("Failed to serialize request body content while detecting inline mode: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean containsMetricsToken(String text) {
        return text != null && text.contains(METRICS_TOKEN);
    }
}
