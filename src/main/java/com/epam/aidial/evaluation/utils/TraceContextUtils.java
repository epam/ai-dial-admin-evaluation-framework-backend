package com.epam.aidial.evaluation.utils;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TraceContextUtils {

    public static String getTraceId() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return context.getTraceId();
    }

    public static String getSpanId() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return context.getSpanId();
    }

    public static String formatTraceParent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        String flags = String.format("%02x", context.getTraceFlags().asByte());
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + flags;
    }
}
