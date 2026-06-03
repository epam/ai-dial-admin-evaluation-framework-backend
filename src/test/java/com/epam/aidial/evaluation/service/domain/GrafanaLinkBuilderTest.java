package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.grafana.GrafanaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GrafanaLinkBuilder")
class GrafanaLinkBuilderTest {

    private static final long FIXED_NOW_MS = 1700000120000L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MS), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GrafanaLinkBuilder builder(String baseUrl, String datasourceUid, int orgId) {
        GrafanaProperties props = new GrafanaProperties();
        props.setBaseUrl(baseUrl);
        props.setTempoDatasourceUid(datasourceUid);
        props.setOrgId(orgId);
        return new GrafanaLinkBuilder(props, objectMapper, FIXED_CLOCK);
    }

    private GrafanaLinkBuilder enabledBuilder() {
        return builder("http://grafana:3000", "tempo", 1);
    }

    private GrafanaLinkBuilder disabledBuilder() {
        return builder("", "tempo", 1);
    }

    @Nested
    @DisplayName("traceUrl")
    class TraceUrl {

        @Test
        @DisplayName("should return Grafana Explore URL when enabled and traceId is present")
        void shouldReturnUrlWhenEnabled() {
            String url = enabledBuilder().traceUrl("abc123def456");

            assertThat(url).isNotNull();
            assertThat(url).startsWith("http://grafana:3000/explore?");
            assertThat(url).contains("schemaVersion=1");
            assertThat(url).contains("orgId=1");
            assertThat(url).contains("abc123def456");
            assertThat(url).contains("tempo");
        }

        @Test
        @DisplayName("should return null when Grafana is disabled (blank base-url)")
        void shouldReturnNullWhenDisabled() {
            assertThat(disabledBuilder().traceUrl("abc123")).isNull();
        }

        @Test
        @DisplayName("should return null when traceId is null")
        void shouldReturnNullForNullTraceId() {
            assertThat(enabledBuilder().traceUrl(null)).isNull();
        }

        @Test
        @DisplayName("should return null when traceId is blank")
        void shouldReturnNullForBlankTraceId() {
            assertThat(enabledBuilder().traceUrl("  ")).isNull();
        }

        @Test
        @DisplayName("should use custom datasource UID")
        void shouldUseCustomDatasourceUid() {
            GrafanaLinkBuilder custom = builder("http://grafana:3000", "my-tempo", 1);
            String url = custom.traceUrl("trace123");

            assertThat(url).contains("my-tempo");
        }
    }

    @Nested
    @DisplayName("runExploreUrl")
    class RunExploreUrl {

        @Test
        @DisplayName("should return Grafana Explore URL with TraceQL for completed run")
        void shouldReturnUrlForCompletedRun() {
            UUID runId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            long startedAt = 1700000000000L;
            long completedAt = 1700000060000L;

            String url = enabledBuilder().runExploreUrl(runId, startedAt, completedAt);

            assertThat(url).isNotNull();
            assertThat(url).startsWith("http://grafana:3000/explore?");
            assertThat(url).contains("550e8400-e29b-41d4-a716-446655440000");
            assertThat(url).contains("eval.run.id");
        }

        @Test
        @DisplayName("should use current time as 'to' boundary when completedAt is null (in-progress run)")
        void shouldUseCurrentTimeWhenCompletedAtIsNull() {
            UUID runId = UUID.randomUUID();
            long startedAt = 1700000000000L;

            String url = enabledBuilder().runExploreUrl(runId, startedAt, null);

            assertThat(url).isNotNull();
            assertThat(url).startsWith("http://grafana:3000/explore?");
            assertThat(url).contains(String.valueOf(FIXED_NOW_MS));
        }

        @Test
        @DisplayName("should return null when startedAt is null (PENDING run)")
        void shouldReturnNullForPendingRun() {
            assertThat(enabledBuilder().runExploreUrl(UUID.randomUUID(), null, null))
                    .isNull();
        }

        @Test
        @DisplayName("should return null when Grafana is disabled")
        void shouldReturnNullWhenDisabled() {
            assertThat(disabledBuilder().runExploreUrl(UUID.randomUUID(), 1L, 2L))
                    .isNull();
        }

        @Test
        @DisplayName("should return null when runId is null")
        void shouldReturnNullForNullRunId() {
            assertThat(enabledBuilder().runExploreUrl(null, 1L, 2L)).isNull();
        }

        @Test
        @DisplayName("should use custom datasource UID")
        void shouldUseCustomDatasourceUid() {
            GrafanaLinkBuilder custom = builder("http://grafana:3000", "custom-ds", 1);
            UUID runId = UUID.randomUUID();

            String url = custom.runExploreUrl(runId, 1000L, 2000L);

            assertThat(url).contains("custom-ds");
        }
    }

    @Nested
    @DisplayName("testCaseAggregateUrl")
    class TestCaseAggregateUrl {

        @Test
        @DisplayName("should return Grafana Explore URL with TraceQL for run + testcase")
        void shouldReturnUrlWithRunAndTestCaseIds() {
            UUID runId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            UUID testCaseId = UUID.fromString("660e8400-e29b-41d4-a716-446655440001");
            long createdAtMs = 1700000000000L;
            long computedAtMs = 1700000060000L;

            String url = enabledBuilder().testCaseAggregateUrl(runId, testCaseId, createdAtMs, computedAtMs);

            assertThat(url).isNotNull();
            assertThat(url).startsWith("http://grafana:3000/explore?");
            assertThat(url).contains("550e8400-e29b-41d4-a716-446655440000");
            assertThat(url).contains("660e8400-e29b-41d4-a716-446655440001");
            assertThat(url).contains("eval.run.id");
            assertThat(url).contains("testcase.id");
        }

        @Test
        @DisplayName("should use current time as 'to' when computedAtMs is null")
        void shouldUseCurrentTimeWhenComputedAtIsNull() {
            UUID runId = UUID.randomUUID();
            UUID testCaseId = UUID.randomUUID();
            long createdAtMs = 1700000000000L;

            String url = enabledBuilder().testCaseAggregateUrl(runId, testCaseId, createdAtMs, null);

            assertThat(url).isNotNull();
            assertThat(url).contains(String.valueOf(FIXED_NOW_MS));
        }

        @Test
        @DisplayName("should return null when Grafana is disabled")
        void shouldReturnNullWhenDisabled() {
            assertThat(disabledBuilder().testCaseAggregateUrl(UUID.randomUUID(), UUID.randomUUID(), 1L, 2L))
                    .isNull();
        }

        @Test
        @DisplayName("should return null when runId is null")
        void shouldReturnNullForNullRunId() {
            assertThat(enabledBuilder().testCaseAggregateUrl(null, UUID.randomUUID(), 1L, 2L))
                    .isNull();
        }

        @Test
        @DisplayName("should return null when testCaseId is null")
        void shouldReturnNullForNullTestCaseId() {
            assertThat(enabledBuilder().testCaseAggregateUrl(UUID.randomUUID(), null, 1L, 2L))
                    .isNull();
        }

        @Test
        @DisplayName("should return null when createdAtMs is null")
        void shouldReturnNullForNullCreatedAtMs() {
            assertThat(enabledBuilder().testCaseAggregateUrl(UUID.randomUUID(), UUID.randomUUID(), null, 2L))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("should return true when base-url is set")
        void shouldReturnTrueWhenConfigured() {
            assertThat(enabledBuilder().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should return false when base-url is blank")
        void shouldReturnFalseWhenBlank() {
            assertThat(disabledBuilder().isEnabled()).isFalse();
        }

        @Test
        @DisplayName("should return false when base-url is null")
        void shouldReturnFalseWhenNull() {
            assertThat(builder(null, "tempo", 1).isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("orgId")
    class OrgId {

        @Test
        @DisplayName("should use configured orgId in URL")
        void shouldUseConfiguredOrgId() {
            GrafanaLinkBuilder custom = builder("http://grafana:3000", "tempo", 5);
            String url = custom.traceUrl("trace123");

            assertThat(url).contains("orgId=5");
            assertThat(url).doesNotContain("orgId=1");
        }
    }
}
