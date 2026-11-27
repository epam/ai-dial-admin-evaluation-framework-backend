package com.epam.aidial.evaluation.functional.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory stand-in for the DIAL Core scheduler application, reached through DIAL Core's application route
 * ({@code /v1/deployments/applications/{appId}/route/v1/schedules/...}). Implements only what EF calls (create,
 * get, update, delete, pause, resume, list runs, get run), records every request with the credential headers it carried,
 * and can be told to fail the next calls.
 */
@Slf4j
public final class SchedulerTestServer implements AutoCloseable {

    /** One request as the scheduler saw it. */
    public record RecordedRequest(
            String method, String path, String query, String authorization, String apiKey, String body) {}

    private static final String ROUTE_MARKER = "/route/v1/schedules";
    private static final String JSON = "application/json";
    private static final int NOT_FOUND = 404;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ObjectNode> schedules = new ConcurrentHashMap<>();
    private final Map<String, List<ObjectNode>> runs = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Queue<Integer> failures = new ConcurrentLinkedQueue<>();
    private final Set<String> failingRunDetails = ConcurrentHashMap.newKeySet();

    public SchedulerTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/deployments/", this::handle);
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start the scheduler test server", e);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Forgets all schedules, runs, recorded requests and queued failures. */
    public void reset() {
        schedules.clear();
        runs.clear();
        requests.clear();
        failures.clear();
        failingRunDetails.clear();
    }

    /** Makes the next request answer with this status and no schedule change. */
    public void failNext(int status) {
        failures.add(status);
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public List<RecordedRequest> requests(String method) {
        return requests.stream().filter(r -> r.method().equals(method)).toList();
    }

    public Optional<JsonNode> schedule(String id) {
        return Optional.ofNullable(schedules.get(id));
    }

    public boolean contains(String id) {
        return schedules.containsKey(id);
    }

    /** Parsed JSON body of the single create (POST to the collection) request. */
    public JsonNode createBody() {
        final List<RecordedRequest> creates = requests("POST").stream()
                .filter(r -> r.path().endsWith("/schedules/"))
                .toList();
        if (creates.size() != 1) {
            throw new IllegalStateException("Expected exactly one create request but saw " + creates.size());
        }
        return mapper.readTree(creates.get(0).body());
    }

    /** Adds a schedule the way an out-of-band client would, bypassing EF. Returns its id. */
    public String seed(String displayName) {
        final String id = UUID.randomUUID().toString();
        final ObjectNode body = mapper.createObjectNode();
        body.put("display_name", displayName);
        body.put("service_id", "dial-native");
        body.put("timezone", "UTC");
        body.set("trigger", mapper.createObjectNode().set("cron", mapper.createObjectNode()));
        body.set("properties", mapper.createObjectNode());
        schedules.put(id, newSchedule(id, body));
        return id;
    }

    /** Removes the schedule, as if it had been deleted directly in the scheduler. */
    public void forget(String id) {
        schedules.remove(id);
    }

    /** Marks the schedule as soft-deleted, as the real scheduler does on delete. */
    public void softDelete(String id) {
        final ObjectNode schedule = schedules.get(id);
        if (schedule != null) {
            schedule.put("is_deleted", true);
        }
    }

    /** Adds a fire without a recorded response and returns its id. */
    public String addRun(String scheduleId, String status, Instant start, Instant end) {
        return addRun(scheduleId, status, start, end, null);
    }

    /**
     * Adds a fire and returns its id. {@code result} is the target's recorded response ({@code {"body": ...,
     * "status_code": 202}} for a fire that reached EF); like the real scheduler it is returned by the fire detail
     * endpoint only, never by the fire list.
     */
    public String addRun(String scheduleId, String status, Instant start, Instant end, JsonNode result) {
        final ObjectNode run = mapper.createObjectNode();
        final String id = UUID.randomUUID().toString();
        run.put("id", id);
        run.put("status", status);
        run.put("start_time", start.toString());
        if (end == null) {
            run.putNull("end_time");
        } else {
            run.put("end_time", end.toString());
        }
        if (result == null) {
            run.putNull("result");
        } else {
            run.set("result", result);
        }
        runs.computeIfAbsent(scheduleId, k -> new CopyOnWriteArrayList<>()).add(run);
        return id;
    }

    /** Makes the detail endpoint of this fire answer 500, as a scheduler hiccup would. */
    public void failRunDetail(String runId) {
        failingRunDetails.add(runId);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final String path = exchange.getRequestURI().getRawPath();
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    path,
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Api-Key"),
                    body));
            final Integer failure = failures.poll();
            if (failure != null) {
                respond(exchange, failure, detail("injected failure"));
                return;
            }
            final int marker = path.indexOf(ROUTE_MARKER);
            if (marker < 0) {
                respond(exchange, NOT_FOUND, detail("not a scheduler route"));
                return;
            }
            final String[] segments = Arrays.stream(
                            path.substring(marker + ROUTE_MARKER.length()).split("/"))
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
            route(exchange, segments, body);
        } catch (RuntimeException e) {
            log.warn("Scheduler test server failed to handle a request", e);
            throw e;
        }
    }

    private void route(HttpExchange exchange, String[] segments, String body) throws IOException {
        final String method = exchange.getRequestMethod();
        if (segments.length == 0 && "POST".equals(method)) {
            create(exchange, body);
        } else if (segments.length == 1) {
            item(exchange, method, segments[0], body);
        } else if (segments.length == 2 && "POST".equals(method) && isPauseOrResume(segments[1])) {
            pauseOrResume(exchange, segments[0], "resume".equals(segments[1]));
        } else if (segments.length == 2 && "GET".equals(method) && "runs".equals(segments[1])) {
            listRuns(exchange, segments[0]);
        } else if (segments.length == 3 && "GET".equals(method) && "runs".equals(segments[1])) {
            getRun(exchange, segments[0], segments[2]);
        } else {
            respond(exchange, NOT_FOUND, detail("unsupported scheduler call"));
        }
    }

    private void create(HttpExchange exchange, String body) throws IOException {
        final String id = UUID.randomUUID().toString();
        final ObjectNode schedule = newSchedule(id, (ObjectNode) mapper.readTree(body));
        schedules.put(id, schedule);
        respond(exchange, 201, schedule);
    }

    private void item(HttpExchange exchange, String method, String id, String body) throws IOException {
        final ObjectNode existing = schedules.get(id);
        if (existing == null) {
            respond(exchange, NOT_FOUND, detail("Schedule not found"));
            return;
        }
        switch (method) {
            case "GET" -> respond(exchange, 200, existing);
            case "PUT" -> {
                final ObjectNode updated = newSchedule(id, (ObjectNode) mapper.readTree(body));
                updated.set("created_at", existing.get("created_at"));
                schedules.put(id, updated);
                respond(exchange, 200, updated);
            }
            case "DELETE" -> {
                schedules.remove(id);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> respond(exchange, 405, detail("method not allowed"));
        }
    }

    private void pauseOrResume(HttpExchange exchange, String id, boolean resume) throws IOException {
        final ObjectNode schedule = schedules.get(id);
        if (schedule == null) {
            respond(exchange, NOT_FOUND, detail("Schedule not found"));
            return;
        }
        if (resume) {
            schedule.put("next_run_time", Instant.now().plusSeconds(3600).toString());
        } else {
            schedule.putNull("next_run_time");
        }
        respond(exchange, 200, schedule);
    }

    private void listRuns(HttpExchange exchange, String id) throws IOException {
        if (!schedules.containsKey(id)) {
            respond(exchange, NOT_FOUND, detail("Schedule not found"));
            return;
        }
        final Map<String, String> query = queryParams(exchange.getRequestURI().getRawQuery());
        final int limit = Integer.parseInt(query.getOrDefault("limit", "20"));
        final int offset = Integer.parseInt(query.getOrDefault("offset", "0"));
        final List<ObjectNode> all = runs.getOrDefault(id, List.of());
        final ArrayNode results = mapper.createArrayNode();
        all.stream()
                .skip(offset)
                .limit(limit)
                .map(SchedulerTestServer::withoutResult)
                .forEach(results::add);
        final ObjectNode page = mapper.createObjectNode();
        page.put("count", all.size());
        page.put("limit", limit);
        page.put("offset", offset);
        page.set("results", results);
        page.putNull("next");
        page.putNull("previous");
        respond(exchange, 200, page);
    }

    private void getRun(HttpExchange exchange, String scheduleId, String runId) throws IOException {
        if (!schedules.containsKey(scheduleId)) {
            respond(exchange, NOT_FOUND, detail("Schedule not found"));
            return;
        }
        if (failingRunDetails.contains(runId)) {
            respond(exchange, 500, detail("injected run detail failure"));
            return;
        }
        final Optional<ObjectNode> run = runs.getOrDefault(scheduleId, List.of()).stream()
                .filter(r -> runId.equals(r.path("id").asString()))
                .findFirst();
        if (run.isEmpty()) {
            respond(exchange, NOT_FOUND, detail("Run not found"));
            return;
        }
        respond(exchange, 200, run.get());
    }

    private static ObjectNode withoutResult(ObjectNode run) {
        final ObjectNode item = run.deepCopy();
        item.remove("result");
        return item;
    }

    private ObjectNode newSchedule(String id, ObjectNode request) {
        final String now = Instant.now().toString();
        final ObjectNode schedule = mapper.createObjectNode();
        schedule.put("id", id);
        schedule.set("display_name", request.get("display_name"));
        schedule.set("service_id", request.get("service_id"));
        schedule.set("description", request.get("description"));
        schedule.put("next_run_time", Instant.now().plusSeconds(3600).toString());
        schedule.set("trigger", request.get("trigger"));
        schedule.set("timezone", request.get("timezone"));
        schedule.set("properties", request.get("properties"));
        schedule.put("is_deleted", false);
        schedule.put("created_by", "scheduler-test-user");
        schedule.put("created_at", now);
        schedule.put("updated_at", now);
        return schedule;
    }

    private ObjectNode detail(String message) {
        return mapper.createObjectNode().put("detail", message);
    }

    private void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
        final byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static boolean isPauseOrResume(String segment) {
        return "pause".equals(segment) || "resume".equals(segment);
    }

    private static Map<String, String> queryParams(String rawQuery) {
        final Map<String, String> params = new ConcurrentHashMap<>();
        if (rawQuery == null) {
            return params;
        }
        final String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
