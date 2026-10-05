package com.epam.aidial.evaluation.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.configuration.properties.dialapp.DialAppProperties;
import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

@DisplayName("DialRouteTriggerClient")
class DialRouteTriggerClientTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    private MockRestServiceServer server;
    private DialAppProperties dialAppProperties;
    private DialRouteTriggerClient client;

    private Logger clientLogger;
    private Level originalLevel;
    private CapturingAppender logAppender;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ObjectProvider<ApiKeyProperties> apiKeyPropertiesProvider = mock(ObjectProvider.class);
        dialAppProperties = new DialAppProperties(apiKeyPropertiesProvider);
        dialAppProperties.setDeploymentName("EF");
        dialAppProperties.setHeartbeatIntervalMs(30000L);
        dialAppProperties.setTriggerReadTimeoutMs(43200000L);

        client = new DialRouteTriggerClient(dialAppProperties, restClient);

        clientLogger = (Logger) LogManager.getLogger(DialRouteTriggerClient.class);
        originalLevel = clientLogger.getLevel();
        clientLogger.setLevel(Level.DEBUG);
        logAppender = new CapturingAppender();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.removeAppender(logAppender);
        logAppender.stop();
        clientLogger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("builds the route URL from the configured deployment name and runId")
    void buildsCorrectRouteUrl() {
        CallerCredential credential = CallerCredential.bearer("jwt-token");
        RequestMatcher matcher = request -> assertThat(request.getURI().getPath())
                .isEqualTo("/v1/deployments/EF/route/api/internal/runs/" + RUN_ID + "/execute");
        server.expect(matcher).andRespond(withSuccess("", MediaType.TEXT_EVENT_STREAM));

        client.triggerEvalRun(RUN_ID, credential);

        server.verify();
    }

    @Test
    @DisplayName("sends the Authorization header exactly as returned by the given CallerCredential")
    void sendsAuthorizationHeaderFromCredential() {
        CallerCredential credential = CallerCredential.bearer("jwt-token");
        RequestMatcher matcher = request ->
                assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer jwt-token");
        server.expect(matcher).andRespond(withSuccess("", MediaType.TEXT_EVENT_STREAM));

        client.triggerEvalRun(RUN_ID, credential);

        server.verify();
    }

    @Test
    @DisplayName("sends the API-Key header exactly as returned by an API_KEY-kind CallerCredential")
    void sendsApiKeyHeaderFromCredential() {
        CallerCredential credential = CallerCredential.apiKey("prk-value");
        RequestMatcher matcher =
                request -> assertThat(request.getHeaders().getFirst("Api-Key")).isEqualTo("prk-value");
        server.expect(matcher).andRespond(withSuccess("", MediaType.TEXT_EVENT_STREAM));

        client.triggerEvalRun(RUN_ID, credential);

        server.verify();
    }

    @Test
    @DisplayName("consumes the SSE response stream fully until EOF")
    void consumesSseStreamUntilEof() {
        CallerCredential credential = CallerCredential.bearer("jwt-token");
        TrackingInputStream stream =
                new TrackingInputStream("event: heartbeat\ndata: {}\n\nevent: heartbeat\ndata: {}\n\n");
        server.expect(req -> {}).andRespond(withSuccess(new InputStreamResource(stream), MediaType.TEXT_EVENT_STREAM));

        client.triggerEvalRun(RUN_ID, credential);

        server.verify();
        assertThat(stream.reachedEof).isTrue();
        assertThat(stream.closed).isTrue();
        assertThat(streamClosedLogs()).isNotEmpty();
    }

    @Test
    @DisplayName("catches IOException during stream consumption and logs at WARN instead of propagating it")
    void ioExceptionDuringStreamConsumptionIsCaughtAndLoggedAtWarn() {
        CallerCredential credential = CallerCredential.bearer("jwt-token");
        FailingInputStream stream = new FailingInputStream();
        server.expect(req -> {}).andRespond(withSuccess(new InputStreamResource(stream), MediaType.TEXT_EVENT_STREAM));

        assertThatCode(() -> client.triggerEvalRun(RUN_ID, credential)).doesNotThrowAnyException();

        server.verify();
        List<LogEvent> warnLogs = logAppender.events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getMessage().getFormattedMessage().contains("IOException while consuming"))
                .toList();
        assertThat(warnLogs).hasSize(1);
    }

    private List<LogEvent> streamClosedLogs() {
        return logAppender.events.stream()
                .filter(e -> e.getMessage().getFormattedMessage().contains("stream closed for run"))
                .toList();
    }

    /** InputStream whose content is read line-by-line and tracks whether it was read to EOF and closed. */
    private static final class TrackingInputStream extends InputStream {
        private final InputStream delegate;
        private volatile boolean reachedEof;
        private volatile boolean closed;

        private TrackingInputStream(String content) {
            this.delegate = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b == -1) {
                reachedEof = true;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n == -1) {
                reachedEof = true;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }

    /**
     * InputStream that yields a single byte successfully (so the RestClient's empty-body probe peek
     * succeeds and the response is handed back as a non-empty stream), then throws {@link IOException}
     * on every subsequent read — simulating a connection reset partway through SSE stream consumption.
     */
    private static final class FailingInputStream extends InputStream {
        private boolean firstByteServed;

        @Override
        public int read() throws IOException {
            if (!firstByteServed) {
                firstByteServed = true;
                return 'e';
            }
            throw new IOException("simulated connection reset");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!firstByteServed) {
                firstByteServed = true;
                b[off] = 'e';
                return 1;
            }
            throw new IOException("simulated connection reset");
        }
    }

    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("capturing", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
