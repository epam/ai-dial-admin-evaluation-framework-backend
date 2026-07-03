package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression tests for the {@code /api/v1/test-suite-runs/status-stream} SSE endpoint.
 *
 * <p>Guards against the double-JSON-encoding regression where event payloads were emitted as
 * string-escaped JSON literals (e.g. {@code "{\"runId\":\"...\"}"}) instead of plain JSON objects
 * (e.g. {@code {"runId":"..."}}). The regression appeared after the Spring Boot 4 / Jackson 3
 * upgrade because the custom {@code JacksonJsonHttpMessageConverter} (registered at the front of
 * the converter list) claims {@code String} payloads and serializes the already-JSON string again.
 */
@DisplayName("Test Suite Run SSE Functional Tests")
public abstract class TestSuiteRunSseFunctionalTests extends BaseFunctionalTest {

    private static final long EVENT_TIMEOUT_MS = 15_000L;

    @Autowired
    private TestSuiteRunSseService sseService;

    @Test
    @DisplayName("connected event data is plain JSON, not a string-escaped JSON literal")
    void connectedEventIsPlainJson() throws Exception {
        final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        final AtomicReference<InputStream> stream = new AtomicReference<>();
        final Thread reader = startReader(events, stream);
        try {
            final SseEvent connected = awaitEvent(events, "connected");
            assertThat(connected.data())
                    .as("connected payload must be plain JSON, not an escaped JSON string")
                    .startsWith("{")
                    .doesNotContain("\\\"")
                    .contains("\"connectionId\"");
        } finally {
            closeQuietly(stream.get());
            reader.interrupt();
        }
    }

    @Test
    @DisplayName("status-update event data is plain JSON, not a string-escaped JSON literal")
    void statusUpdateEventIsPlainJson() throws Exception {
        final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        final AtomicReference<InputStream> stream = new AtomicReference<>();
        final Thread reader = startReader(events, stream);
        try {
            // Ensure the emitter is registered server-side before broadcasting.
            awaitEvent(events, "connected");

            final TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .status("COMPLETED")
                    .build();
            sseService.notifyStatusUpdate(run);

            final SseEvent statusUpdate = awaitEvent(events, "status-update");
            assertThat(statusUpdate.data())
                    .as("status-update payload must be plain JSON, not an escaped JSON string")
                    .startsWith("{")
                    .doesNotContain("\\\"")
                    .contains("\"runId\":\"" + run.getId() + "\"")
                    .contains("\"status\":\"COMPLETED\"");
        } finally {
            closeQuietly(stream.get());
            reader.interrupt();
        }
    }

    private Thread startReader(BlockingQueue<SseEvent> events, AtomicReference<InputStream> stream) {
        final Thread reader = new Thread(() -> {
            try {
                final HttpClient client = HttpClient.newHttpClient();
                final HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl("/test-suite-runs/status-stream")))
                        .GET()
                        .build();
                final HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                stream.set(response.body());
                readEvents(response.body(), events);
            } catch (IOException | InterruptedException e) {
                // Stream closed by the test or interrupted on teardown — expected.
                Thread.currentThread().interrupt();
            }
        });
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private void readEvents(InputStream in, BlockingQueue<SseEvent> events) throws IOException {
        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String name = null;
        final StringBuilder data = new StringBuilder();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            if (line.isEmpty()) {
                if (name != null) {
                    events.offer(new SseEvent(name, data.toString()));
                }
                name = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()));
            }
        }
    }

    private SseEvent awaitEvent(BlockingQueue<SseEvent> events, String name) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + EVENT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            final SseEvent event = events.poll(EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (event != null && name.equals(event.name())) {
                return event;
            }
        }
        throw new AssertionError("Did not receive '" + name + "' SSE event within " + EVENT_TIMEOUT_MS + "ms");
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // best-effort teardown
        }
    }

    private record SseEvent(String name, String data) {}
}
