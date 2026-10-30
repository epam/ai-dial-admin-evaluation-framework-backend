package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.functional.support.CoreRouteTestServer;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector;
import com.epam.aidial.evaluation.web.security.apikey.IntrospectionResult;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

public abstract class CoreRoutedTracingDisabledFunctionalTests extends AbstractMultiTurnFunctionalTest {
    @MockitoBean
    private CoreApiKeyIntrospector introspector;

    @Autowired
    private OpenTelemetry telemetry;

    @Autowired
    private TestCaseRunResultRepository results;

    protected abstract CoreRouteTestServer core();

    @BeforeEach
    void configureCore() {
        core().configure(URI.create(baseUrl()), telemetry);
        when(introspector.introspect(anyString()))
                .thenReturn(new IntrospectionResult("caller", List.of("admin"), false));
    }

    @Test
    @DisplayName("Core-routed evaluation with tracing disabled still completes and persists no fabricated trace ID")
    void disabledTracingDoesNotBreakRoutedEvaluation() {
        final var suite = createChatSuite("No tracing");
        createSingleTurnCase(suite.getDatasetId(), "case", Map.of("prompt", "hello"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    assertThat(Span.current().getSpanContext().isValid()).isFalse();
                    return chatReply("answer");
                });
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("caller-token");
        final var response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                new HttpEntity<>(
                        TestSuiteRunRequestDto.builder()
                                .runConfig(
                                        RunConfigDto.builder().numberOfRuns(1).build())
                                .build(),
                        headers),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final var run = awaitRunTerminal(response.getBody().getId(), 20);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(results.findAll(
                                List.of(FilterCondition.builder()
                                        .field("runId")
                                        .operator(FilterOperator.EQ)
                                        .rawValue(run.getId().toString())
                                        .build()),
                                run.getCreatedAt(),
                                null,
                                100)
                        .content())
                .singleElement()
                .satisfies(result -> assertThat(result.getTraceId()).isNull());
        assertThat(core().authorization()).isEqualTo("Bearer caller-token");
    }
}
