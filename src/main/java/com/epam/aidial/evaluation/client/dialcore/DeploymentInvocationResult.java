package com.epam.aidial.evaluation.client.dialcore;

import java.io.Closeable;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;

/**
 * Result from a DIAL Core deployment invocation with streaming support.
 * Implements AutoCloseable to ensure the HTTP response and event stream are properly closed.
 *
 * <p>In production use, {@code responseResource} is the underlying {@link org.springframework.http.client.ClientHttpResponse}
 * whose {@code close()} releases both the stream and the HTTP connection. Test code that constructs
 * results directly with a plain {@link InputStream} uses the 5-arg constructor (responseResource=null),
 * and {@code close()} falls back to closing {@code eventStream} directly.
 *
 * @param statusCode       HTTP status code from DIAL Core
 * @param streaming        true if response Content-Type is text/event-stream
 * @param body             parsed JSON body (non-streaming), null for streaming
 * @param eventStream      raw SSE InputStream (streaming), null for non-streaming
 * @param responseHeaders  response headers
 * @param responseResource underlying response resource to close; may be null (tests / non-streaming)
 */
public record DeploymentInvocationResult(
        int statusCode,
        boolean streaming,
        Object body,
        InputStream eventStream,
        HttpHeaders responseHeaders,
        Closeable responseResource)
        implements AutoCloseable {

    /** Convenience constructor for test code and non-streaming callers (no response resource). */
    public DeploymentInvocationResult(
            int statusCode, boolean streaming, Object body, InputStream eventStream, HttpHeaders responseHeaders) {
        this(statusCode, streaming, body, eventStream, responseHeaders, null);
    }

    @Override
    public void close() throws Exception {
        if (responseResource != null) {
            responseResource.close();
        } else if (eventStream != null) {
            eventStream.close();
        }
    }
}
