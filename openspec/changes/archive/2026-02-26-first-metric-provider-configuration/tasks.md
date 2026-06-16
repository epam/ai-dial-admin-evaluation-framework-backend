## 1. Application configuration

- [x] 1.1 Add one entry to `metric-providers.providers` in application.yml with env-driven placeholders for id, base-url, connect-timeout-ms, and read-timeout-ms (e.g. `METRIC_PROVIDERS_PROVIDERS_0_ID`, `METRIC_PROVIDERS_PROVIDERS_0_BASE_URL`, `METRIC_PROVIDERS_PROVIDERS_0_CONNECT_TIMEOUT_MS`, `METRIC_PROVIDERS_PROVIDERS_0_READ_TIMEOUT_MS`). Per design: use empty defaults or commented block so startup behavior is clear when env vars are unset.

## 2. Documentation

- [x] 2.1 Update docs/configuration.md: in Metric Providers Configuration, add a subsection or table listing the environment variable names for the first provider entry (`METRIC_PROVIDERS_PROVIDERS_0_ID`, `METRIC_PROVIDERS_PROVIDERS_0_BASE_URL`, `METRIC_PROVIDERS_PROVIDERS_0_CONNECT_TIMEOUT_MS`, `METRIC_PROVIDERS_PROVIDERS_0_READ_TIMEOUT_MS`) and note that they override the first list entry so operators can configure a single provider without editing YAML.

## 3. Verification

- [x] 3.1 Run `./gradlew clean build` to ensure no regressions; no Java changes in this change, existing tests and checkstyle apply.
