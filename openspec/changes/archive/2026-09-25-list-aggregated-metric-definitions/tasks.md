## 1. Aggregated metric definition list

- [x] 1.1 Add controller and service tests for the static aggregated-list route, suite isolation, disabled/invalid inclusion, and empty results; verify focused controller and Postgres functional tests pass.
- [x] 1.2 Expose the existing all-aggregated repository result through a read-only DTO-facing service method and `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/aggregated`; verify the new tests pass.
- [x] 1.3 Add array-schema OpenAPI annotations and minimal/full response examples; verify `/v3/api-docs` exposes both examples.
- [x] 1.4 Sync `openspec/specs/aggregated-metric-definition/spec.md` and update `openspec/specs/README.md` if its summary becomes inaccurate; verify `openspec validate list-aggregated-metric-definitions --strict` passes.
- [x] 1.5 Run `./gradlew spotlessApply`, `./gradlew check`, and `git diff --check`; verify all pass.