## Context

`DeploymentReferenceDto` (`service.domain.dto`) is the single in-memory representation of a suite's deployment reference — there is no separate domain model. It currently carries `id`, `name`, `version`. It is embedded by `TestSuiteRequestDto`, `TestSuiteResponseDto`, `TestSuiteCloneRequestDto`, and `SuiteSnapshotDto`, and is serialized **whole** to two JSONB columns via `JsonbMapper`/`ObjectMapper`:
- `test_suites.deployment_ref` — written by `PostgresTestSuiteRepository`; DTO↔String via `TestSuiteMapper.toEntity/update/toCloneEntity` → `JsonbMapper.map(...)`.
- Inside `test_suite_runs.suite_snapshot` — as `SuiteSnapshotDto.deploymentRef`, serialized as part of the whole snapshot in `TestSuiteEvaluationJob`.

Snapshot deserialization is `@JsonIgnoreProperties(ignoreUnknown = true)`, so adding a field is backward compatible on read.

## Goals / Non-Goals

**Goals:**
- Add an optional free-text `type` (`String`, max 50) to `DeploymentReferenceDto` and have it persist and read back on both paths.
- Keep the change additive and backward compatible.

**Non-Goals:**
- No enum / constrained value set (free-text per product decision).
- No `dial-toolset` value handling (toolset suites use `McpDeploymentReferenceDto`).
- No backend derivation of `type` from the DIAL Core catalog — the field is client-supplied, like the rest of `deploymentRef`.
- No DB migration, no jOOQ regeneration, no mapper/repository signature changes.

## Decisions

- **Whole-object serialization ⇒ zero wiring.** Because the entire DTO is serialized to JSONB, the new field round-trips automatically. Only the DTO gains a field; `JsonbMapper`, `TestSuiteMapper`, `PostgresTestSuiteRepository`, and the embedding DTOs are untouched.
- **Optional, no `@NotBlank`.** Only `@Size(max = 50)`. Existing clients and already-stored suites/snapshots read back `type = null`. Mirrors the existing `McpDeploymentReferenceDto.type` precedent.
- **`JsonbMapper.extractDeploymentId` unaffected** — it reads only the `id` key from the JSONB tree.
- **Examples/docs** use `dial-model` / `dial-application`. Full deployment-suite example JSONs get the field; the minimal example intentionally omits it to demonstrate optionality.

## Risks / Trade-offs

- **Free-text, not validated against a value set** — accepts any ≤50-char string. Accepted trade-off; FE owns the vocabulary, and a future enum tightening remains possible without a data migration.
- **Historical snapshots** created before this change have no `type`; they deserialize to `null` — expected and harmless.
