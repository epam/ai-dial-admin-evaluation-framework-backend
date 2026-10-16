# `SuiteTarget` sealed hierarchy

What a suite invokes is modelled internally as a sealed `SuiteTarget` (`com.epam.aidial.evaluation.runner.model.target`, `evaluation-runner-core`, shared with `eval-cli`), not as a flat `suiteType` plus four nullable ref fields.

**Why.** The flat shape (`suiteType`, `deploymentRef`, `routesRef`, `mcpDeploymentRef`, `toolRef`) forced `if (suiteType == X)` branches and duplicated null-defaulting (legacy null type => DEPLOYMENT) in every consumer. Adding `ROUTE` multiplied both. A target now carries exactly the refs its kind needs, and the compiler enforces exhaustiveness.

## Two-level split

```
SuiteTarget (sealed)
├── HttpTarget (sealed)
│   ├── DeploymentTarget(ref)
│   └── RouteTarget(ref)
└── McpToolTarget(ref, tool)
```

Most code only distinguishes HTTP vs MCP (turn loop, worker, run orchestration, try-it-out), so those switch on `HttpTarget`/`McpToolTarget`. Only URL building (`DialCoreUrlBuilder.buildUrl(HttpTarget, String)`) and request-model validation care about `DeploymentTarget` vs `RouteTarget`, along with the snapshot builder's ref field, try-it-out's per-kind "reference is required" message and the CLI's deployment-ref override. Each record exposes `type()` returning the matching `SuiteType`.

## Boundary rule

The sealed type is **internal and never serialized**. These stay **flat**:

- wire DTOs (`TestSuiteRequestDto`, `TestSuiteResponseDto`, `SuiteSnapshotDto`)
- DB JSONB columns on `test_suites`
- the persisted `suite_snapshot` JSON
- Query DSL fields such as `deployment_ref::id`

The target is derived **once per entry point** and then passed down:

| Where | Derivation |
|-------|------------|
| runner-core / `eval-cli` | `SuiteTargets.fromSnapshot(SuiteSnapshotDto)`, `SuiteTargets.fromSuiteResponse(TestSuiteResponseDto)` |
| main app | `SuiteTargetResolver.fromEntity(TestSuite)` (JSONB strings via `JsonbMapper`), `SuiteTargetResolver.fromRequest(TestSuiteRequestDto)` |

`EvaluationContext` carries only `target`; consumers never re-read the flat fields.

## Single validation point

`SuiteTargetResolver.fromRequest` is the one place enforcing "exactly one target with its hard-required fields" (HTTP 400 `ValidationException`): DEPLOYMENT needs `deploymentRef`; MCP_TOOL needs `mcpDeploymentRef` + `toolRef` (and no `additionalRequests`); ROUTE needs `routesRef` + `endpointRef` + `requestTemplate`. Other kinds' ref fields must be empty. Do not add per-kind null checks elsewhere (`TestSuiteRequestValidator` and callers go through the resolver). The records themselves do not null-check: snapshots and entities are trusted persisted data.

## Legacy null type

Suites created before `suiteType` existed have it null, which means DEPLOYMENT. Use `SuiteTargets.typeOrDefault(SuiteType)` (or the `from*` factories, which apply it) — never re-implement the default. `SuiteTargets.fromSnapshot` applies the same default itself because the snapshot's `suiteType` is a String.

## Style rule

Branch with an exhaustive `switch` (type or record patterns) and **no `default`**, so adding a variant is a compile error at every site. An `instanceof` guard is acceptable when only one variant is special (e.g. `target instanceof RouteTarget`).

## Adding a fourth target kind

1. Add the value to `SuiteType` and a record under `model.target` implementing `SuiteTarget` (or `HttpTarget` if it is invoked over HTTP; update the `permits`).
2. Add flat ref field(s) to the DTOs/snapshot, a Flyway migration for the column(s), regenerate jOOQ, and update `docs/database-schema.md`.
3. Extend the `SuiteTargets` factories and `SuiteTargetResolver.fromEntity`/`fromRequest` (hard-required fields + "others must be empty" checks).
4. Fix every compile error from the now non-exhaustive switches (no `default` to hide them).
5. Update OpenAPI examples and tests (`SuiteTargetResolver`, `SuiteTargets`, a functional create/run path).

## Deliberately not modelled

The **invocation shape** is still flat: `requestTemplate` / `endpointRef` / `additionalRequests` (HTTP) vs `argumentTemplate` (MCP) are validated and consumed independently in `TemplateVariableService` and `SuiteValidationService`, keyed off `SuiteType` (via `SuiteTargets.typeOrDefault`), not the target. Folding them into the target records is a possible follow-up, out of scope here.
