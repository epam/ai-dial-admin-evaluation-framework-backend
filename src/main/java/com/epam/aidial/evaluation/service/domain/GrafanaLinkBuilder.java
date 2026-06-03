package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.grafana.GrafanaProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds Grafana Explore deep-link URLs for traces and run-scoped queries.
 * Returns {@code null} when Grafana integration is disabled (base-url blank).
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class GrafanaLinkBuilder {

    private static final long TIME_BUFFER_MS = Duration.ofMinutes(5).toMillis();

    private final GrafanaProperties grafanaProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Returns a Grafana Explore URL that opens a single trace by its traceId,
     * or {@code null} when traceId is null/blank or Grafana is not configured.
     */
    public String traceUrl(String traceId) {
        if (!isEnabled() || traceId == null || traceId.isBlank()) {
            return null;
        }
        return buildExploreUrl("traceql", traceId, null, null);
    }

    /**
     * Returns a Grafana Explore TraceQL URL aggregating all spans for a specific test case
     * within a run (deployment call + all metric evaluation calls), or {@code null} when
     * Grafana is not configured or required inputs are null.
     */
    public String testCaseAggregateUrl(UUID runId, UUID testCaseId, Long createdAtMs, Long computedAtMs) {
        if (!isEnabled() || runId == null || testCaseId == null || createdAtMs == null) {
            return null;
        }
        String query = "{.eval.run.id=\"" + runId + "\" && .testcase.id=\"" + testCaseId + "\"}";
        long from = createdAtMs - TIME_BUFFER_MS;
        long to = computedAtMs != null ? computedAtMs + TIME_BUFFER_MS : clock.millis();
        return buildExploreUrl("traceql", query, from, to);
    }

    /**
     * Returns a Grafana Explore TraceQL URL scoped to all test-case traces for a run,
     * or {@code null} when Grafana is not configured or startedAt is null (PENDING runs).
     */
    public String runExploreUrl(UUID runId, Long startedAt, Long completedAt) {
        if (!isEnabled() || runId == null || startedAt == null) {
            return null;
        }
        String query = "{.eval.run.id=\"" + runId + "\"}";
        long from = startedAt - TIME_BUFFER_MS;
        long to = completedAt != null ? completedAt + TIME_BUFFER_MS : clock.millis();
        return buildExploreUrl("traceql", query, from, to);
    }

    boolean isEnabled() {
        String baseUrl = grafanaProperties.getBaseUrl();
        return baseUrl != null && !baseUrl.isBlank();
    }

    private String buildExploreUrl(String queryType, String query, Long fromMs, Long toMs) {
        String datasourceUid = grafanaProperties.getTempoDatasourceUid();

        ObjectNode queryNode = objectMapper.createObjectNode();
        queryNode.put("refId", "A");
        ObjectNode dsRef = queryNode.putObject("datasource");
        dsRef.put("type", "tempo");
        dsRef.put("uid", datasourceUid);
        queryNode.put("queryType", queryType);
        queryNode.put("query", query);

        ObjectNode pane = objectMapper.createObjectNode();
        pane.put("datasource", datasourceUid);
        ArrayNode queries = pane.putArray("queries");
        queries.add(queryNode);
        ObjectNode range = pane.putObject("range");
        range.put("from", fromMs != null ? String.valueOf(fromMs) : "now-1h");
        range.put("to", toMs != null ? String.valueOf(toMs) : "now");

        ObjectNode panes = objectMapper.createObjectNode();
        panes.set("a", pane);

        String panesJson = serializePanes(panes);
        String encodedPanes = URLEncoder.encode(panesJson, StandardCharsets.UTF_8);

        return UriComponentsBuilder.fromUriString(grafanaProperties.getBaseUrl())
                .path("/explore")
                .queryParam("schemaVersion", 1)
                .queryParam("panes", encodedPanes)
                .queryParam("orgId", grafanaProperties.getOrgId())
                .build(true)
                .toUriString();
    }

    private String serializePanes(ObjectNode panes) {
        try {
            return objectMapper.writeValueAsString(panes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Grafana panes JSON", e);
        }
    }
}
