## 1. Data model & migration

- [x] 1.1 Add `V1.37__AddRoutesRefToTestSuites.sql` (`ALTER TABLE test_suites ADD COLUMN routes_ref JSONB;`) in `db/migration/meta/POSTGRES/` (done: nullable, no default, no index)
- [x] 1.2 Regenerate jOOQ sources via `./gradlew generateJooq` (done: `TestSuites`/`TestSuitesRecord` carry `routes_ref`; drift guard green)
- [x] 1.3 Add `routesRef` (JSON String) to `TestSuite` in `data.db.model` (done: pure carrier)
- [x] 1.4 Map `routes_ref` in `TestSuiteRecordMapper` and write it on insert/update/clone in `PostgresTestSuiteRepository` (done: round-trips, null for non-ROUTE)
- [x] 1.5 Update `docs/database-schema.md` (done: `suite_type` values, `routes_ref` row, `deployment_ref` says DEPLOYMENT suites, `additional_requests` says same suite HTTP target)

## 2. DTOs & validation

- [x] 2.1 Add `ROUTE` to `SuiteType` in runner-core `model` (done: enum serialises as `ROUTE`)
- [x] 2.2 Create `RouteReferenceDto` in runner-core `dto` with `@NotBlank`, `@Size(max=255)`, `@Pattern` canonical identity (done: bad name yields 400 with the pattern message)
- [x] 2.3 Add `ROUTE_REFERENCE_NAME_PATTERN`, `ROUTE_REFERENCE_NAME_MESSAGE`, `MAX_ROUTE_REFERENCE_NAME_LENGTH` to `RunnerValidationConstants` (done: single definition)
- [x] 2.4 Add `routesRef` + `@Schema` descriptions to `TestSuiteRequestDto`, `TestSuiteResponseDto`, `SuiteSnapshotDto`; `suiteType` text lists ROUTE; snapshot endpointRef/requestTemplate descriptions say "(DEPLOYMENT and ROUTE suites)" (done: OpenAPI shows them)
- [x] 2.5 Implement ROUTE rules in `TestSuiteRequestValidator`: required `routesRef`/`endpointRef`/`requestTemplate`, rejected `deploymentRef`/`mcpDeploymentRef`/`toolRef`/`argumentTemplate`, `routesRef` rejected on DEPLOYMENT and MCP_TOOL, exact messages and order per design (done: unit tests green)
- [x] 2.6 Add `routesRef` (de)serialisation to `JsonbMapper` (done: DTO <-> JSONB round trip)
- [x] 2.7 Map `routesRef` in `TestSuiteMapper` incl. clone inheritance (no clone override) (done: `TestSuiteMapperCloneTest` green)

## 3. Snapshot

- [x] 3.1 `SuiteSnapshotBuilder`: for ROUTE set `routesRef`, leave `deploymentRef` null, keep HTTP fields; DEPLOYMENT snapshots have null `routesRef` (done: `SuiteSnapshotBuilderTest` green)

## 4. Execution

- [x] 4.1 `DialCoreUrlBuilder`: ROUTE returns the resolved path unchanged; reject non-root-relative, `//`, `?`/`#`, `\`/control chars with the specified messages; DEPLOYMENT rules untouched (done: `DialCoreUrlBuilderTest` green)
- [x] 4.2 `TurnLoopExecutor`: skip resolved request-model validation for ROUTE and build the path via the ROUTE URL rule (done: `TurnLoopExecutorTest` green, status SUCCESS with mismatching `model`)

## 5. Try-it-out

- [x] 5.1 `TryItOutService`: ROUTE precondition "Route reference is required for try-it-out", Core root-path invocation, model validation skipped, both endpoints and chain (done: `TryItOutServiceTest` green)

## 6. eval-cli

- [x] 6.1 `RunOrchestrationService`: reject ROUTE before contract validation/deployment resolution with `ROUTE suite execution is not supported by eval-cli` (done: `RunOrchestrationServiceTest` green)

## 7. Query DSL

- [x] 7.1 `TestSuiteRunQueryFields`: add `routes_ref` descriptor (`name`) between `deployment_ref` and `mcp_deployment_ref` (done: field count 22)
- [x] 7.2 `TestSuitesSchemaProvider` and `PostgresTestSuiteEntityResolver`: expose `routes_ref::name` on `test_suites` (done: schema endpoint lists it, filter works)
- [x] 7.3 `test_suite_runs` schema provider/resolver: `routes_ref::name` from `suite_snapshot` (done: provider and resolver tests green)

## 8. OpenAPI

- [x] 8.1 Whitelist `route` in `OpenApiExampleCustomizer` (done: example name accepted)
- [x] 8.2 Add the six `route` example files under `src/main/resources/openapi/examples/` (done: files named per registered paths)

## 9. Tests & quality

- [x] 9.1 `TestSuiteRequestValidatorTest` ROUTE nested class (done: green)
- [x] 9.2 `DialCoreUrlBuilderTest` (done: green)
- [x] 9.3 `SuiteSnapshotBuilderTest` (done: green)
- [x] 9.4 `TryItOutServiceTest` (done: green)
- [x] 9.5 `TestSuiteMapperCloneTest` (done: green)
- [x] 9.6 `TestSuitesSchemaProviderTest`, `TestSuiteRunsSchemaProviderTest` (done: green)
- [x] 9.7 `PostgresTestSuiteEntityResolverTest`, `PostgresTestSuiteRunEntityResolverTest` (done: green)
- [x] 9.8 runner-core `TurnLoopExecutorTest` (done: `./gradlew :evaluation-runner-core:test` green)
- [x] 9.9 eval-cli `RunOrchestrationServiceTest` (done: `./gradlew :eval-cli:test` green)
- [x] 9.10 Functional (`PostgresFunctionalTests` nested) `TestSuiteFunctionalTests` incl. OpenAPI route examples, with helper `MetaTestDataHelper.createTestSuiteWithRoutesRef` (done: green)
- [x] 9.11 Functional `SuiteSnapshotFunctionalTests` (done: ROUTE run completes, Core receives `/validate/items`)
- [x] 9.12 Functional `TryItOutFunctionalTests` (done: HTTP 200, invoker path `/validate/items`)
- [x] 9.13 Functional `TestSuiteStructuredQueryFunctionalTests`, `TestSuiteRunStructuredQueryFunctionalTests` (done: green)
- [x] 9.14 `./gradlew spotlessApply`, `checkstyleMain checkstyleTest`, full `./gradlew build` (done: all green)

## 10. Docs & specs

- [x] 10.1 `docs/database-schema.md` updated (see 1.5)
- [x] 10.2 Sync delta specs into `openspec/specs` via `/opsx:sync` at archive time
- [x] 10.3 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: test-suites, multi-request-suite and test-suite-clone summaries mention ROUTE / the suite HTTP target / inherited routesRef; index reflects current specs)

Note: no `config.yaml` or `AGENTS.md` task; the feature follows the existing suite-type pattern and adds no new package, layer or convention.
