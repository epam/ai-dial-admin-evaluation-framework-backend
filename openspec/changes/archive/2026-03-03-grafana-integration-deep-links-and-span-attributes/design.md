## Context

When an eval run produces unexpected results, engineers look up the `traceId` from the API response and paste it into Grafana Explore manually. There is no direct link. Additionally, finding _all_ traces for a specific run requires knowing every individual `traceId`, because `eval.run.id` is not a span attribute and cannot be used as a Tempo TraceQL filter.

Current state:
- `ExecutionInfoResponseDto.traceId` — already present, populated by `EvaluationWorker`.
- `TryItOutResponseDto.traceId` — already present, populated by `TryItOutService`.
- `TestSuiteRunResponseDto` — has `startedAt`/`completedAt` but no Grafana link.
- `EvaluationWorker` span `eval.testcase.execute` has `testcase.id` and `run.index` but not `eval.run.id` or `eval.suite.id`.
- `TryItOutService` span `try-it-out.invoke` has zero attributes.

## Goals / Non-Goals

**Goals:**
- Add `grafanaTraceUrl` to `ExecutionInfoResponseDto` and `TryItOutResponseDto` — a ready-to-click Grafana Explore URL for the individual trace.
- Add `grafanaExploreUrl` to `TestSuiteRunResponseDto` — a Grafana Explore TraceQL URL scoped to all test-case traces for that run, with the run's time range pre-set.
- Add `eval.run.id` and `eval.suite.id` span attributes to `eval.testcase.execute` in `EvaluationWorker`.
- Add `eval.suite.id` span attribute to `try-it-out.invoke` in `TryItOutService`.
- Gate all URL generation on `app.grafana.base-url` (empty → disabled).

**Non-Goals:**
- Grafana API calls (annotations, label updates, dashboard provisioning).
- Trace search or aggregation server-side.
- Changes to the Grafana datasource name or UID (configurable via `app.grafana.tempo-datasource-uid`).
- Breaking changes to existing API fields.

## Decisions

### Decision: Pure URL construction — no Grafana API calls
Grafana Explore deep links are fully client-side URL constructions. No HTTP call to Grafana is needed. The service simply builds a `{baseUrl}/explore?...` URL using the `traceId` and run timestamps.

**Alternative considered**: Grafana Annotations API (`POST /api/annotations`) — rejected because it couples availability to Grafana's uptime, requires API key management, and is one-directional (write only, not navigable). URL construction is zero-risk, zero-overhead.

### Decision: New `GrafanaLinkBuilder` @Component in `service.domain`
URL construction is factored into a dedicated injectable component rather than inline in services. This keeps `EvaluationWorker`, `TryItOutService`, and mappers free of URL-building logic, and makes unit testing trivial.

**Alternative considered**: Static utility method in `utils` package — rejected because it would not be injected and could not be conditionally configured (the builder reads `GrafanaProperties`).

### Decision: New `GrafanaProperties` @ConfigurationProperties(prefix = "app.grafana")
Follows the existing `@ConfigurationProperties` pattern used by `DialCoreProperties`. Three properties:
- `base-url` (String, default `""`) — feature disabled when blank.
- `tempo-datasource-uid` (String, default `"tempo"`) — Tempo datasource UID as configured in Grafana.
- `org-id` (int, default `1`) — Grafana organization ID used in Explore URLs. Required for multi-org Grafana deployments.

No validation (`@NotBlank`) on `base-url` intentionally — blank = feature off, which is the default.

### Decision: `grafanaExploreUrl` on `TestSuiteRunResponseDto` uses TraceQL `{.eval.run.id="<uuid>"}`
This requires that `eval.run.id` is present as a span attribute on every `eval.testcase.execute` span. The time range in the URL is set to `[startedAt - 5 min, completedAt + 5 min]` (or `now` when run is still in progress) to keep the query fast. When `startedAt` is null (PENDING run — no traces exist yet), `runExploreUrl` returns `null`.

**Alternative considered**: `{traceId=~"id1|id2|..."}` union — rejected because it requires the client to know all trace IDs upfront, which is not available at the `TestSuiteRunResponseDto` level without a join.

### Decision: Where to inject GrafanaLinkBuilder
- `EvaluationWorker.buildResult()` — inject `GrafanaLinkBuilder`, set `grafanaTraceUrl` in the returned result; the URL field propagates through `ExecutionInfoResponseDto` via existing `AnalyticsResultService` / mapper chain.
- `TryItOutService.invokeAndBuildResponse()` — inject `GrafanaLinkBuilder`, set `grafanaTraceUrl` in `TryItOutResponseDto`.
- `TestSuiteRunService` (or its mapper) — inject `GrafanaLinkBuilder`, set `grafanaExploreUrl` when building `TestSuiteRunResponseDto`.

To propagate the URL through the analytics pipeline, `TestCaseRunResult` (data model) will carry a transient `grafanaTraceUrl` field (or the URL is generated on read in the mapper — prefer on-read to avoid storing a UI concern in the DB).

**Decision**: Generate URLs on-read in mappers/services, not in `EvaluationWorker`. `EvaluationWorker` already stores `traceId` in `TestCaseRunResult`; `GrafanaLinkBuilder.traceUrl(traceId)` is called in `AnalyticsResultService` when building `ExecutionInfoResponseDto`. This avoids a DB schema change.

## Risks / Trade-offs

- [Risk] Grafana `base-url` or datasource UID misconfigured → URLs returned but open a 404 in Grafana.
  → Mitigation: Document clearly; no validation at startup (intentional — service should work without Grafana configured).
- [Risk] `TestSuiteRunResponseDto.grafanaExploreUrl` has an overly wide time range if runs span hours.
  → Mitigation: +/- 5 min buffer is a UX convenience, not a query bound. Tempo queries are still scoped by `eval.run.id` attribute.
- [Risk] Grafana Explore URL format changes in future Grafana versions.
  → Mitigation: URL is generated in a single `GrafanaLinkBuilder` component — one place to update.
- [Trade-off] `eval.run.id` attribute added to every test-case span increases span attribute count by 1.
  → Accepted: attribute overhead is negligible (~36 chars) and enables run-scoped search.

## Open Questions

- None — scope is well-defined. Grafana datasource UID is configurable; default `"tempo"` matches standard Grafana+Tempo setups.
