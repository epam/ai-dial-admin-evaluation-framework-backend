## Why

The frontend cannot tell what *kind* of DIAL deployment a `deploymentRef` points at (a model vs an application) when rendering a test suite or a test-case run summary. The `deploymentRef` contract only carries `id`, `name`, and `version`. FE needs a `type` signal to render an "application reference" appropriately.

## What Changes

- Add an optional free-text `type` field (`String`, `@Size(max = 50)`) to `DeploymentReferenceDto`, positioned after `version`.
- The field carries the deployment kind, expected values `dial-model` / `dial-application`. It is **optional/nullable** — no `@NotBlank`, so existing clients and already-stored suites/snapshots read back with `type = null`.
- It persists and reads back transparently everywhere `deploymentRef` flows — on the suite (`test_suites.deployment_ref` JSONB) and inside the frozen run snapshot (`test_suite_runs.suite_snapshot` JSONB). The whole DTO is serialized via `JsonbMapper`/`ObjectMapper`, and snapshot deserialization already tolerates unknown/missing fields, so **no DB migration, no jOOQ regen, no mapper/repository changes** are required.
- Update OpenAPI `@Schema` example on the field and the "full" deployment-suite example JSON files. Not a breaking change.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `test-suites`: the DEPLOYMENT suite's `deploymentRef` object gains an optional `type` field (free-text, max 50) that is accepted on create/update/clone, persisted, and returned on read; omission is valid and yields `null`.

## Impact

- **Code**: `service.domain.dto.DeploymentReferenceDto` (add field). No changes to embedding DTOs (`TestSuiteRequestDto`, `TestSuiteResponseDto`, `TestSuiteCloneRequestDto`, `SuiteSnapshotDto`), mappers (`JsonbMapper`, `TestSuiteMapper`), or the repository — the field flows through by reference/whole-object serialization. `JsonbMapper.extractDeploymentId` is unaffected (still reads only `id`).
- **API**: additive field on the `deploymentRef` request/response contract for test-suite create/update/clone and the run-snapshot read. Backward compatible.
- **Persistence**: JSONB columns `test_suites.deployment_ref` and `test_suite_runs.suite_snapshot` — no schema change (JSONB stores the extra key transparently).
- **Docs/Examples**: `@Schema` example on the DTO field; "full" example JSON files under `src/main/resources/openapi/examples/`; `docs/database-schema.md` if the `deployment_ref` JSONB shape is enumerated there.
- **Tests**: functional round-trip assertions on both persistence paths (suite GET, run-snapshot GET).
