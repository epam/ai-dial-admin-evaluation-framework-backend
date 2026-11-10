package com.epam.aidial.evaluation.functional.support;

import com.epam.aidial.evaluation.constants.SecurityConstants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/** Local HTTP Core route emulator: continues tracing, mints a test PRK, and forwards EF's SSE stream. */
@Slf4j
public final class CoreRouteTestServer implements AutoCloseable {
    public static final String PRK = "core-test-prk";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile URI backend;
    private volatile OpenTelemetry telemetry;
    private volatile int rejectionStatus;
    private final AtomicReference<String> correlation = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<InputStream> activeStream = new AtomicReference<>();
    private volatile boolean disconnecting;

    public CoreRouteTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/deployments/", this::forward);
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start Core route emulator", e);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void configure(URI backend, OpenTelemetry telemetry) {
        this.backend = backend;
        this.telemetry = telemetry;
        rejectionStatus = 0;
        disconnecting = false;
        correlation.set(null);
        authorization.set(null);
        failure.set(null);
    }

    public void reject(int status) {
        rejectionStatus = status;
    }

    public String correlationId() {
        return correlation.get();
    }

    public String authorization() {
        return authorization.get();
    }

    public Throwable failure() {
        return failure.get();
    }

    public boolean hasActiveStream() {
        return activeStream.get() != null;
    }

    public void disconnect() throws IOException {
        disconnecting = true;
        final InputStream stream = activeStream.get();
        if (stream != null) {
            stream.close();
        }
    }

    private void forward(HttpExchange exchange) throws IOException {
        correlation.set(exchange.getRequestHeaders().getFirst(SecurityConstants.CORRELATION_ID_HEADER_NAME));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getRequestBody().close();
        if (rejectionStatus != 0) {
            exchange.sendResponseHeaders(rejectionStatus, -1);
            exchange.close();
            return;
        }
        final Context incoming = telemetry
                .getPropagators()
                .getTextMapPropagator()
                .extract(Context.root(), exchange, (TextMapGetter<HttpExchange>)
                        (carrier, key) -> carrier.getRequestHeaders().getFirst(key));
        final Span core = telemetry
                .getTracer("test-core")
                .spanBuilder("core.route")
                .setParent(incoming)
                .startSpan();
        try (Scope ignored = incoming.with(core).makeCurrent()) {
            final String path = exchange.getRequestURI().getPath();
            final String internal = path.substring(path.indexOf("/route/") + "/route".length());
            final HttpRequest.Builder request = HttpRequest.newBuilder(backend.resolve(internal))
                    .header("Api-Key", PRK)
                    .POST(HttpRequest.BodyPublishers.noBody());
            final String id = correlation.get();
            if (id != null) {
                request.header(SecurityConstants.CORRELATION_ID_HEADER_NAME, id);
            }
            telemetry
                    .getPropagators()
                    .getTextMapPropagator()
                    .inject(Context.current(), request, HttpRequest.Builder::header);
            final var response =
                    HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            activeStream.set(response.body());
            exchange.getResponseHeaders()
                    .set(
                            "Content-Type",
                            response.headers().firstValue("Content-Type").orElse("text/event-stream"));
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (var body = response.body();
                    var output = exchange.getResponseBody()) {
                body.transferTo(output);
            }
        } catch (IOException e) {
            if (!disconnecting) {
                failure.set(e);
                log.warn("Core route emulator stream failed", e);
            } else {
                log.debug("Core route emulator intentionally disconnected", e);
            }
        } catch (InterruptedException e) {
            failure.set(e);
            Thread.currentThread().interrupt();
            log.warn("Core route emulator interrupted", e);
        } finally {
            activeStream.set(null);
            core.end();
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
