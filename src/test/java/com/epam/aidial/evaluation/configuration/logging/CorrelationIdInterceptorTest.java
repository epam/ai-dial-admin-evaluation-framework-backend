package com.epam.aidial.evaluation.configuration.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.constants.SecurityConstants;
import com.epam.aidial.evaluation.utils.CorrelationIdContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Request correlation scopes")
class CorrelationIdInterceptorTest {

    private final CorrelationIdInterceptor interceptor = new CorrelationIdInterceptor();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Object handler = new Object();

    @AfterEach
    void clearCorrelation() {
        MDC.remove(SecurityConstants.CORRELATION_ID_MDC_KEY);
    }

    @Test
    @DisplayName("valid client correlation IDs are echoed and removed at synchronous request completion")
    void clientIdIsScopedToRequest() {
        request.addHeader(SecurityConstants.CORRELATION_ID_HEADER_NAME, "clientCorrelation123");
        interceptor.preHandle(request, response, handler);
        assertThat(CorrelationIdContext.currentId()).isEqualTo("clientCorrelation123");
        assertThat(response.getHeader(SecurityConstants.CORRELATION_ID_HEADER_NAME))
                .isEqualTo("clientCorrelation123");
        interceptor.afterCompletion(request, response, handler, null);
        assertThat(CorrelationIdContext.currentId()).isNull();
    }

    @Test
    @DisplayName("missing client correlation IDs use the active trace ID and are cleared on async handoff")
    void traceIdIsUsedAndClearedAtAsyncHandoff() {
        final SpanContext context = SpanContext.create(
                "11111111111111111111111111111111",
                "2222222222222222",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            interceptor.preHandle(request, response, handler);
            assertThat(CorrelationIdContext.currentId()).isEqualTo(context.getTraceId());
            assertThat(response.getHeader(SecurityConstants.CORRELATION_ID_HEADER_NAME))
                    .isEqualTo(context.getTraceId());
            interceptor.afterConcurrentHandlingStarted(request, response, handler);
            assertThat(CorrelationIdContext.currentId()).isNull();
            assertThat(Span.current().getSpanContext()).isEqualTo(context);
        }
    }

    @Test
    @DisplayName("invalid client IDs without an active span receive a valid random fallback")
    void invalidIdUsesRandomFallback() {
        request.addHeader(SecurityConstants.CORRELATION_ID_HEADER_NAME, "invalid!");
        interceptor.preHandle(request, response, handler);
        assertThat(response.getHeader(SecurityConstants.CORRELATION_ID_HEADER_NAME))
                .matches("[a-zA-Z0-9]{16}");
        assertThat(CorrelationIdContext.currentId())
                .isEqualTo(response.getHeader(SecurityConstants.CORRELATION_ID_HEADER_NAME));
    }

    @Test
    @DisplayName("captured correlation IDs restore the prior thread value even when the task throws")
    void taskRestoresPreviousIdOnFailure() {
        MDC.put(SecurityConstants.CORRELATION_ID_MDC_KEY, "capturedCorrelation");
        final Runnable wrapped = CorrelationIdContext.wrap(() -> {
            assertThat(CorrelationIdContext.currentId()).isEqualTo("capturedCorrelation");
            throw new IllegalStateException("failure");
        });
        MDC.put(SecurityConstants.CORRELATION_ID_MDC_KEY, "previousCorrelation");
        assertThatThrownBy(wrapped::run).isInstanceOf(IllegalStateException.class);
        assertThat(CorrelationIdContext.currentId()).isEqualTo("previousCorrelation");
    }

    @Test
    @DisplayName("absent captured correlation clears stale task context and restores it afterwards")
    void absentCaptureDoesNotInheritStaleId() {
        final Runnable wrapped = CorrelationIdContext.wrap(
                () -> assertThat(CorrelationIdContext.currentId()).isNull());
        MDC.put(SecurityConstants.CORRELATION_ID_MDC_KEY, "staleCorrelation");
        wrapped.run();
        assertThat(CorrelationIdContext.currentId()).isEqualTo("staleCorrelation");
    }
}
