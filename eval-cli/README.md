# eval-cli

A standalone Spring Boot CLI tool that clones "standard" test suites from a source EF instance,
fetches their configuration and test cases, executes them against a configured target deployment
using the shared `evaluation-runner-core` execution engine, and imports the results back into the
source EF via its existing `runs/import` API.

## Commands

| Command    | Description |
|------------|-------------|
| `clone`    | Ensures a `<name>_<suffix>` clone of each selected suite exists on the source EF (reuses if present). |
| `fetch`    | Retrieves suite config and all test cases from the source EF; persists a JSON bundle under `cli.work-dir`. |
| `run`      | Executes all test cases against the target deployment; writes results to CSV under `cli.work-dir`. |
| `import`   | Imports a produced CSV into the cloned suite on the source EF; metric computation is triggered automatically. |
| `evaluate` | Runs all four steps in sequence for each selected suite (`clone` → `fetch` → `run` → `import`). |

All five commands require `--suites` (comma-separated source EF suite UUIDs); `clone`, `fetch`, and
`evaluate` also require `--clone-suffix`. Neither has a configuration or environment-variable
fallback — they are always CLI-only, since which suites/suffix to use is inherently a per-invocation
choice, not a stable environment default. `run` and `evaluate` also accept `--deployment-id`, but it is
**optional**: when omitted, execution falls back to the fetched suite's own recorded deployment
reference instead of requiring an explicit override. Everything else (source/target auth, target host)
is env-var-only — see [Configuration](#configuration).

## Quick start

```bash
export EVAL_SOURCE_API_KEY=<source EF API key>
export DIAL_CORE_URL=http://localhost:8085
export DIAL_CORE_API_KEY=<target DIAL Core API key>

java -jar eval-cli.jar evaluate \
  --suites 78ca0a5f-da3d-45fd-bb36-b44380c105eb \
  --clone-suffix eval \
  --deployment-id my-model
```

### Docker

No pre-built image is published — this repo's shared release tooling assumes one Docker image per
repository, and eval-cli is a second one. Build the image yourself instead, ideally from a pinned tag
or commit rather than a floating branch, so your pipeline's behavior stays reproducible:

```bash
git clone --branch <tag-or-commit> <this-repo-url> eval-cli-src
cd eval-cli-src
docker build -f eval-cli/Dockerfile -t eval-cli:<tag-or-commit> .
```

Running it requires no local JDK; the entrypoint forwards all arguments to the CLI, so subcommands and
flags are passed straight through to `docker run`. Mount a host directory to `/app/eval-cli-work` if
you want the fetched suite bundles and result CSVs (written under `cli.work-dir`) to persist outside
the container:

```bash
docker run --rm \
  -e EVAL_SOURCE_BASE_URL=http://host.docker.internal:8080 \
  -e EVAL_SOURCE_API_KEY=<source EF API key> \
  -e DIAL_CORE_URL=http://host.docker.internal:8085 \
  -e DIAL_CORE_API_KEY=<target DIAL Core API key> \
  -v $(pwd)/eval-cli-work:/app/eval-cli-work \
  eval-cli:<tag-or-commit> \
  evaluate \
    --suites 78ca0a5f-da3d-45fd-bb36-b44380c105eb \
    --clone-suffix eval \
    --deployment-id my-model
```

## Configuration

All properties can be supplied via environment variables (shown in the **Environment Variable** column)
or through an `application.yml` / Spring property override. Defaults are the values applied when the
variable is absent.

### Source EF connection (`eval.source.*`)

| Property | Environment Variable | Default | Required | Description |
|----------|---------------------|---------|----------|-------------|
| `eval.source.base-url` | `EVAL_SOURCE_BASE_URL` | `http://localhost:8080` | Yes | Base URL of the source EF instance. |
| `eval.source.api-key` | `EVAL_SOURCE_API_KEY` | _(empty)_ | Yes | API key sent as the `Api-Key` header on every request to the source EF. |
| `eval.source.connect-timeout-ms` | `EVAL_SOURCE_CONNECT_TIMEOUT_MS` | `5000` | No | TCP connection timeout to the source EF (ms). |
| `eval.source.read-timeout-ms` | `EVAL_SOURCE_READ_TIMEOUT_MS` | `120000` | No | Read timeout for source EF HTTP responses (ms). |

### Target authentication (`dial.components.core.api-key`)

The target environment (the DIAL Core instance fronting the deployment under evaluation) is
authenticated via an `Api-Key` header — the same mechanism used for the source EF connection above,
and the same one the EF backend itself uses for DIAL Core file operations — since this standalone CLI
has no signed-in user session to propagate a JWT from. It is **env-var-only**, like
`eval.source.api-key`: no CLI flag, no file — in a CI job (this tool's primary deployment context), the
platform's own secret store → env var injection already gets automatic log redaction and leaves no
on-disk artifact to clean up.

| Property | Environment Variable | Default | Required | Description |
|----------|---------------------|---------|----------|-------------|
| `dial.components.core.api-key` | `DIAL_CORE_API_KEY` | _(empty)_ | Yes | API key sent as the `Api-Key` header on every request to the target deployment. |

### Suite selection (`--suites`), clone suffix (`--clone-suffix`), and run metadata (`cli.*`)

Suite selection and the clone suffix are **CLI-flag-only**, with no configuration or environment-variable
fallback — see the [Commands](#commands) table for which commands accept which flag. Which suites/suffix
to use is always a per-invocation, CI-log-visible choice, never a stable environment default. Unlike
these two, `--deployment-id` (also CLI-flag-only) is optional — see the [Commands](#commands) table.

| Property | Environment Variable | Default | Required | Description |
|----------|---------------------|---------|----------|-------------|
| `cli.work-dir` | `CLI_WORK_DIR` | `./eval-cli-work` | Yes | Directory where fetched suite bundles and result CSVs are written. |
| `cli.test-run-name` | `CLI_TEST_RUN_NAME` | _(empty)_ | No | Optional human-readable name written into the imported run's metadata. |

### Execution tuning (`cli.run.*`)

| Property | Environment Variable | Default | Required | Description |
|----------|---------------------|---------|----------|-------------|
| `cli.run.concurrency-level` | `CLI_RUN_CONCURRENCY_LEVEL` | `4` | Yes | Number of test cases executed concurrently (virtual threads). |
| `cli.run.rate-limit-rps` | `CLI_RUN_RATE_LIMIT_RPS` | _(unbounded)_ | No | Optional rate limit in requests per second sent to the target deployment. |
| `cli.run.request-timeout-ms` | `CLI_RUN_REQUEST_TIMEOUT_MS` | `3600000` | Yes | Per-request timeout for deployment invocations (ms). Min: 1000. |
| `cli.run.max-retries` | `CLI_RUN_MAX_RETRIES` | `3` | Yes | Maximum number of retries per test case on transient failures. |
| `cli.run.retry-delay-ms` | `CLI_RUN_RETRY_DELAY_MS` | `1000` | Yes | Initial delay between retries (ms). |
| `cli.run.retry-backoff-multiplier` | `CLI_RUN_RETRY_BACKOFF_MULTIPLIER` | `2.0` | Yes | Exponential backoff multiplier applied to `retry-delay-ms` on each retry. Min: 1.0. |
| `cli.run.max-retry-delay-ms` | `CLI_RUN_MAX_RETRY_DELAY_MS` | `30000` | Yes | Maximum delay between retries regardless of backoff (ms). |
| `cli.run.result-batch-size` | `CLI_RUN_RESULT_BATCH_SIZE` | `50` | Yes | Number of results flushed to CSV per write batch. |
| `cli.run.max-response-size-bytes` | `CLI_RUN_MAX_RESPONSE_SIZE_BYTES` | `10485760` | Yes | Maximum response body size accepted from the target deployment (bytes). Default: 10 MiB. |
| `cli.run.cancellation-grace-period-ms` | `CLI_RUN_CANCELLATION_GRACE_PERIOD_MS` | `30000` | Yes | Grace period to wait for in-flight tasks after a cancellation signal before force-stopping (ms). |

### Target DIAL Core host (`dial.components.core.*`)

`base-url`/timeouts/retry are provided by `evaluation-runner-core`'s `DialCoreProperties` binding;
`api-key` is this module's own `TargetProperties` bound to the same prefix (see
[Target authentication](#target-authentication-dialcomponentscoreapi-key) above). Together they
configure the target environment's DIAL Core endpoint used for deployment invocations. There is no
CLI-flag override for `base-url` — it is env-var-only, set once per target environment.

| Property | Environment Variable | Default | Required | Description |
|----------|---------------------|---------|----------|-------------|
| `dial.components.core.base-url` | `DIAL_CORE_URL` | `http://localhost:8085` | Yes | Base URL of the target DIAL Core instance. |
| `dial.components.core.api-key` | `DIAL_CORE_API_KEY` | _(empty)_ | Yes | API key sent as the `Api-Key` header on every request to the target deployment. |
| `dial.components.core.connect-timeout-ms` | `DIAL_CORE_CONNECT_TIMEOUT_MS` | `5000` | No | TCP connection timeout to DIAL Core (ms). |
| `dial.components.core.read-timeout-ms` | `DIAL_CORE_READ_TIMEOUT_MS` | `30000` | No | Default read timeout for DIAL Core HTTP responses (ms). |
| `dial.components.core.retry.max-attempts` | `DIAL_CORE_RETRY_MAX_ATTEMPTS` | `3` | No | Maximum retry attempts for DIAL Core client calls. |
| `dial.components.core.retry.delay-ms` | `DIAL_CORE_RETRY_DELAY_MS` | `1000` | No | Initial retry delay for DIAL Core client calls (ms). |
| `dial.components.core.retry.multiplier` | `DIAL_CORE_RETRY_MULTIPLIER` | `2.0` | No | Backoff multiplier for DIAL Core client retries. |
| `dial.components.core.try-out.read-timeout-ms` | `DIAL_CORE_TRY_OUT_READ_TIMEOUT_MS` | `3600000` | No | Long-poll read timeout for deployment invocations (ms). Should be ≥ `cli.run.request-timeout-ms`. |

### Shared `evaluation-runner-core` bounds (`test-suite-run.*`, `sse-event-processing.*`)

`evaluation-runner-core`'s `EvaluationWorker` and SSE parsing require these two property groups to be
bound regardless of module; the actual per-run concurrency/timeout/retry values used by `eval-cli` come
from `cli.run.*` above, not from `test-suite-run.*` — only `execution.header-blacklist` is read at
request-build time (headers a test case cannot override, including the `Api-Key` auth header).

| Property | Environment Variable | Default | Required | Description |
|----------|---------------------|---------|----------|-------------|
| `test-suite-run.execution.default-concurrency-level` | `TEST_SUITE_RUN_DEFAULT_CONCURRENCY_LEVEL` | `4` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.execution.max-concurrency-level` | `TEST_SUITE_RUN_MAX_CONCURRENCY_LEVEL` | `50` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.execution.default-request-timeout-ms` | `TEST_SUITE_RUN_DEFAULT_REQUEST_TIMEOUT_MS` | `3600000` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.execution.max-request-timeout-ms` | `TEST_SUITE_RUN_MAX_REQUEST_TIMEOUT_MS` | `3600000` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.execution.result-batch-size` | `TEST_SUITE_RUN_RESULT_BATCH_SIZE` | `50` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.execution.max-response-size-bytes` | `TEST_SUITE_RUN_MAX_RESPONSE_SIZE_BYTES` | `10485760` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.execution.cancellation-grace-period-ms` | `TEST_SUITE_RUN_CANCELLATION_GRACE_PERIOD_MS` | `30000` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.execution.header-blacklist` | _(list, not env-overridable here)_ | `Authorization, Api-Key, Host, Content-Length, Transfer-Encoding, Connection, traceparent, tracestate` | Yes | Headers a test case's own headers may never override. |
| `test-suite-run.retry.default-max-retries` | `TEST_SUITE_RUN_DEFAULT_MAX_RETRIES` | `0` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.retry.max-max-retries` | `TEST_SUITE_RUN_MAX_MAX_RETRIES` | `10` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.retry.default-retry-delay-ms` | `TEST_SUITE_RUN_DEFAULT_RETRY_DELAY_MS` | `1000` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.retry.max-retry-delay-ms` | `TEST_SUITE_RUN_MAX_RETRY_DELAY_MS` | `30000` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.retry.default-retry-backoff-multiplier` | `TEST_SUITE_RUN_DEFAULT_RETRY_BACKOFF_MULTIPLIER` | `2.0` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.retry.max-retry-backoff-multiplier` | `TEST_SUITE_RUN_MAX_RETRY_BACKOFF_MULTIPLIER` | `10.0` | Yes | Unused by eval-cli's own execution path; must bind. |
| `test-suite-run.run-inputs.retention-days` | `TEST_SUITE_RUN_RUN_INPUTS_RETENTION_DAYS` | `7` | Yes | Unused by eval-cli (no DB retention job runs here); must bind. |
| `sse-event-processing.max-total-duration-ms` | `SSE_MAX_TOTAL_DURATION_MS` | `3600000` | Yes | Absolute cap on how long a single SSE stream may be parsed during a streaming deployment invocation. |

### OpenTelemetry (`otel.*`)

`evaluation-runner-core` pulls in `opentelemetry-spring-boot-starter`, which enables the OTel SDK by
default and exports to `http://localhost:4318` unless overridden. Disabled by default here so a CLI
run doesn't spam `Connection refused` errors when no local OTLP collector is running.

| Property | Environment Variable | Default | Required | Description |
|----------|---------------------|---------|----------|-------------|
| `otel.sdk.disabled` | `OTEL_SDK_DISABLED` | `true` | Yes | Set to `false` (and optionally set `OTEL_EXPORTER_OTLP_ENDPOINT`) to export traces/metrics/logs to a real OTLP collector. |

## Notes

- **Authentication**: both the source EF (`eval.source.api-key` / `EVAL_SOURCE_API_KEY`) and target
  DIAL Core (`dial.components.core.api-key` / `DIAL_CORE_API_KEY`) credentials use the same `Api-Key`
  header mechanism and are static, env-var-only secrets — no CLI flag, no file.
- **Idempotency**: `evaluate` (and `clone`) is safe to re-run. If a `<name>_<suffix>` clone already
  exists on the source EF it is reused; a fresh `fetch` / `run` / `import` cycle produces a new run
  import against the same clone.
- **No DB dependency**: `eval-cli` is DB-free by design. It only communicates with the source EF
  over its public REST API and with the target deployment via DIAL Core.
