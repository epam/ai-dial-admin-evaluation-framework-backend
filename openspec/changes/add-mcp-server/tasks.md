## 1. Governance (owned by this umbrella; done when child artifacts exist and reviews pass)

- [ ] 1.1 Create child change `mcp-foundation` with proposal/specs/design/tasks per D2, D3, D7, D8, D12 and the `mcp-tools-deployments` delta spec (`list_deployments`, `get_deployment`); verify `openspec validate --change mcp-foundation` passes and `/opsx:review` reports no blockers
- [ ] 1.2 Create child change `mcp-test-suites` (`create_test_suite` with private dataset per D5, `get_test_suite`, `update_test_suite` per D6, `delete_test_suite`) with `mcp-tools-test-suites` delta spec; verify validate + review
- [ ] 1.3 Create child change `mcp-try-out` (`try_out` by `data` or `testCaseId`, full response body, extracted columns, extraction warnings) with `mcp-tools-try-out` delta spec; verify validate + review
- [ ] 1.4 Create child change `mcp-test-cases` (`set_test_case_schema`, `get_test_case_schema`, `add_test_cases`, `list_test_cases`, `update_test_case`, `delete_test_case`) with `mcp-tools-test-cases` delta spec; verify validate + review
- [ ] 1.5 Create child change `mcp-metrics` (`list_metric_declarations`, `get_metric_declaration`, `add_suite_metric`, `list_suite_metrics`, `update_suite_metric`, `remove_suite_metric`) with `mcp-tools-metrics` delta spec; verify validate + review
- [ ] 1.6 Create child change `mcp-runs` (`run_test_suite`, `get_run`, `list_runs`, `cancel_run`) with `mcp-tools-runs` delta spec; verify validate + review
- [ ] 1.7 Create child change `mcp-results` (`get_run_results` with row cap per D9, `get_run_summary`) with `mcp-tools-results` delta spec; resolve the two Open Questions in design.md; verify validate + review

## 2. Child delivery (each task is done when the child change is implemented, `./gradlew clean build` passes, and the child is archived)

- [ ] 2.1 Deliver and archive `mcp-foundation` — verify: `/mcp` answers `initialize` + `tools/list` in a functional test, 401 without credentials in `oidc` mode, `LayeredArchitectureTest` has the `mcp` layer, `docs/configuration.md` lists all `spring.ai.mcp.server.*` and `ef.mcp-server.*` properties
- [ ] 2.2 Deliver and archive `mcp-test-suites` — verify: functional test creates a suite via MCP and asserts a bound PRIVATE dataset exists; compensation test leaves no unbound suite; `NOT_SUPPORTED` returned for `additionalRequests`/`MCP_TOOL`
- [ ] 2.3 Deliver and archive `mcp-try-out` — verify: functional test against the stub deployment returns response body + `extractedColumns` + `extractionWarnings`
- [ ] 2.4 Deliver and archive `mcp-test-cases` — verify: schema set + cases added via MCP are visible through repositories; `NOT_SUPPORTED` for `multiTurnData`; `INVALID_OPERATION` for a suite without dataset
- [ ] 2.5 Deliver and archive `mcp-metrics` — verify: `get_metric_declaration` returns schemas as JSON objects; `add_suite_metric` persists a TSMD with bindings
- [ ] 2.6 Deliver and archive `mcp-runs` — verify: `run_test_suite` returns before completion; `get_run` reaches terminal status in the functional test; `cancel_run` works on a running run
- [ ] 2.7 Deliver and archive `mcp-results` — verify: `get_run_results` returns all rows with `truncated=false` under the cap and exactly `max-rows` with `truncated=true` above it; `get_run_summary` returns per-metric aggregates

## 3. Umbrella close-out (after all children are archived)

- [ ] 3.1 Sync `mcp-server` and `security` delta specs to `openspec/specs/` via `/opsx:sync`; verify with `git diff` that main specs gained content and lost nothing
- [ ] 3.2 Set `mcp-server` requirement statuses to Implemented with implementation notes pointing to existing code; verify every referenced path exists
- [ ] 3.3 Update openspec/config.yaml per Config Maintenance Policy (done: Architecture list has the `mcp` package/layer, Layering Principle mentions `mcp → service` only, Feature Surface mentions the MCP server)
- [ ] 3.4 Update AGENTS.md per AGENTS.md Maintenance guidelines (done: Architecture Overview lists the `mcp` layer, Unique Patterns row links `docs/patterns/mcp-server.md`, `docs/key-packages.md` lists `mcp.*`)
- [ ] 3.5 Write `docs/patterns/mcp-server.md` (layer rule, model isolation, error contract, unsupported-feature guard, provisioning compensation, session limitation) and link it from `docs/patterns/README.md`
- [ ] 3.6 Update openspec/specs/README.md per Spec Index Maintenance Policy (done: index lists `mcp-server` and every `mcp-tools-<group>` spec with correct status)
- [ ] 3.7 Run `./gradlew clean build` and archive this change via `/opsx:archive` following `rules.archive` in `openspec/config.yaml`
