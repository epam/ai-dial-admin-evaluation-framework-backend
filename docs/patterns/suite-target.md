# `SuiteTarget` / `SuiteDefinition` sealed hierarchy

What a suite invokes is modelled internally as a sealed `SuiteTarget` (`com.epam.aidial.evaluation.runner.model.target`, `evaluation-runner-core`, shared with `eval-cli`), not as a flat `suiteType` plus four nullable ref fields.

**Why.** The flat shape (`suiteType`, `deploymentRef`, `routesRef`, `mcpDeploymentRef`, `toolRef`) forced `if (suiteType == X)` branches (and, before the type became non-null in Java, null-defaulting in every consumer). Adding `ROUTE` multiplied both. A target now carries exactly the refs its kind needs, and the compiler enforces exhaustiveness.

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

(Target only. For the full target + invocation shape see the `SuiteDefinition` derivation table below.)

`EvaluationContext` carries only `suite` (a `SuiteDefinition`, below; `getTarget()` derives its target); consumers never re-read the flat fields.

## Single validation point

`SuiteTargetResolver.fromRequest` is the one place enforcing "exactly one target with its hard-required fields" (HTTP 400 `ValidationException`): DEPLOYMENT needs `deploymentRef`; MCP_TOOL needs `mcpDeploymentRef` + `toolRef` (and no `additionalRequests`); ROUTE needs `routesRef` + `endpointRef` + `requestTemplate`. Other kinds' ref fields must be empty. Do not add per-kind null checks elsewhere (`TestSuiteRequestValidator` and callers go through the resolver). The records themselves do not null-check: snapshots and entities are trusted persisted data.

## Legacy null type

`test_suites.suite_type` is `NOT NULL DEFAULT 'DEPLOYMENT'` since migration V1.15, so a loaded `TestSuite` never has a null type. `TestSuiteRequestDto` and `TestSuite` default `suiteType` to DEPLOYMENT (`@Builder.Default`; the DTO also uses `@JsonSetter(nulls = SKIP)`, so omitted or explicit-null JSON yields DEPLOYMENT). The only tolerant seams are `SuiteTargets.fromSnapshot` (old snapshot JSON, String type) and `SuiteTargets.fromSuiteResponse` (response from an older remote server). `typeOrDefault` no longer exists: never re-add null-defaulting in consumers.

## Style rule

Branch with an exhaustive `switch` (type or record patterns) and **no `default`**, so adding a variant is a compile error at every site. An `instanceof` guard is acceptable when only one variant is special (e.g. `target instanceof RouteTarget`).

## Invocation shape: `SuiteDefinition`

`SuiteTarget` says WHERE a suite calls; `SuiteDefinition` (`runner.model.suite`, `evaluation-runner-core`) pairs it with WHAT is sent:

```
SuiteDefinition (sealed)
├── HttpSuite(HttpTarget target, List<RequestExecutionSpec> chain)   // chain[0] = suite's own request, then additionalRequests
└── McpSuite(McpToolTarget target, argumentTemplate, inputBindings, responseColumns)
```

`SuiteDefinition` exposes `target()` and `responseColumns()`. `RequestExecutionSpec` (moved from `runner.job` to `runner.model.suite`) is one request of the HTTP chain.

**Why pair them.** Illegal combinations (an MCP target with a request chain, an HTTP target with an `argumentTemplate`) are unrepresentable. `EvaluationContext` carries one value (`suite`) instead of nine nullable fields. Consumers use exhaustive `switch`es (`case HttpSuite h`, `case McpSuite m`, no `default`) in `EvaluationWorker`, `SuiteValidationService`, `TemplateVariableService`, `TryItOutService` and the CLI `EvaluationContextFactory.resolveSuite`, instead of branching on `SuiteType`. `TurnLoopExecutor` receives the `HttpTarget` directly.

### Derivation

| Where | Derivation |
|-------|------------|
| runner-core / `eval-cli` | `SuiteDefinitions.fromSnapshot(SuiteSnapshotDto)`, `SuiteDefinitions.fromSuiteResponse(TestSuiteResponseDto)`; the single HTTP chain builder is `SuiteDefinitions.httpChain` |
| main app, persisted rows | `SuiteDefinitionResolver.fromEntity(TestSuite)` — no validation; also used for revalidation of persisted suites |
| main app, incoming request | `SuiteDefinitionResolver.fromRequest(TestSuiteRequestDto)` — delegates target validation to `SuiteTargetResolver.fromRequest` (same 400s, same order) |

HTTP chain: request #0 comes from the suite's singular fields (`requestTemplate`, `endpointRef`, `inputBindings`, `responseColumns`, `requestName`), followed by `additionalRequests[i]` as request `i+1`. `ResolvedRequestService.planChain(UUID, UUID, HttpSuite)` / `planChainForVariables(HttpSuite)` and `RequestChainExecutor` consume the chain; there is no second spec builder. `testCaseSchema` stays suite-level (`EvaluationContext.snapshotTestCaseSchema`).

### Cheap vs full

Use `SuiteTargetResolver` / `SuiteTargets` when only the target is needed (createRun guards, immutability check, mappers): the full definition deserializes every JSONB column (templates, bindings, columns, additional requests). Build a `SuiteDefinition` only where the invocation shape is consumed.

### Boundary and soft fields

Still flat on the wire, in DB JSONB and in the snapshot JSON; the definition is derived once per entry point and never serialized. Request #0 of a DEPLOYMENT suite may legitimately have null `endpointRef` / `requestTemplate`: these are soft warnings in `SuiteValidationService`, not hard-required (only ROUTE hard-requires them, via `SuiteTargetResolver.fromRequest`). The records do not null-check.

## Adding a fourth target kind

1. Add the value to `SuiteType` and a record under `model.target` implementing `SuiteTarget` (or `HttpTarget` if it is invoked over HTTP; update the `permits`).
2. Add flat ref field(s) to the DTOs/snapshot, a Flyway migration for the column(s), regenerate jOOQ, and update `docs/database-schema.md`.
3. Extend the `SuiteTargets` factories and `SuiteTargetResolver.fromEntity`/`fromRequest` (hard-required fields + "others must be empty" checks).
4. Decide which `SuiteDefinition` variant the target belongs to (`HttpSuite` for an `HttpTarget`; `McpSuite` for MCP-like), or add a new variant; extend `SuiteDefinitions` (`fromSnapshot`/`fromSuiteResponse`) and `SuiteDefinitionResolver` (`fromEntity`/`fromRequest`).
5. Fix every compile error from the now non-exhaustive switches (no `default` to hide them).
6. Update OpenAPI examples and tests (`SuiteTargetResolver`, `SuiteTargets`, `SuiteDefinitions`, `SuiteDefinitionResolver`, a functional create/run path).

## Not modelled

Target-related data is mostly modelled; `TestSuiteService.validateSuiteTypeImmutability` reads the type directly (compares two `SuiteType`s, where a target object would add nothing). The one deliberate pass-through is `TestSuiteMapper.toCloneEntity`, which copies the source entity's raw `suiteType` (and flat ref columns) rather than going through a target or definition.
