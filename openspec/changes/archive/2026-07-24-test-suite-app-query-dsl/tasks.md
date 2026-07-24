## 1. Query Translation — `JsonbFieldResolver`

- [x] 1.1 Add `DEPLOYMENT_REF_FIELD = "deployment_ref"` and `MCP_DEPLOYMENT_REF_FIELD = "mcp_deployment_ref"` constants to `JsonbFieldResolver` alongside the existing backing-column constants
- [x] 1.2 Add `deployment_ref::` and `mcp_deployment_ref::` dispatch branches in `JsonbFieldResolver.resolve()`, each delegating to the existing `textPath()` helper with the respective backing-column constant
- [x] 1.3 Run `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.translate.StructuredQueryBuilderTest"` to confirm no regressions; fix any failures

## 2. Schema Discovery — `TestSuitesSchemaProvider`

- [x] 2.1 In `TestSuitesSchemaProvider` constructor, after calling `schemaResolver.resolve(TEST_SUITES)`, append 6 `QuerySchemaFieldDto` entries: `deployment_ref::id`, `deployment_ref::name`, `deployment_ref::version` (source `"deployment_ref"`) and `mcp_deployment_ref::id`, `mcp_deployment_ref::name`, `mcp_deployment_ref::type` (source `"mcp_deployment_ref"`), all typed `STRING`
- [x] 2.2 Run `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.TestSuitesSchemaProviderTest"` — update existing assertions to include the 6 new field entries and confirm they pass

## 3. Entity Resolver — `PostgresTestSuiteEntityResolver`

- [x] 3.1 Inject `JsonPathAccessor` into `PostgresTestSuiteEntityResolver`'s constructor (add it as a new constructor parameter alongside the existing `DSLContext` and `JooqTableSchemaResolver`)
- [x] 3.2 In the constructor, after computing the base bindings from `schemaResolver.bindings(TEST_SUITES)`, add 6 `QueryFieldBinding` entries to the map: `deployment_ref::id/name/version` (each backed by `jsonPathAccessor.jsonbAtAsText(DEPLOYMENT_REF, DSL.val("id"/"name"/"version"))`, typed `STRING`) and `mcp_deployment_ref::id/name/type` similarly over `MCP_DEPLOYMENT_REF`
- [x] 3.3 Use `HashMap` (or `LinkedHashMap`) to make the static bindings map mutable before sealing it as `Collections.unmodifiableMap`, since the existing `JooqTableSchemaResolver.bindings()` already returns a map — merge into a new mutable map then wrap

## 4. Unit Tests

- [x] 4.1 Create `PostgresTestSuiteEntityResolverTest` in `src/test/java/.../experimental/query/service/repository/`: verify that `bindings(query)` contains `deployment_ref::name` (STRING), `deployment_ref::id` (STRING), `deployment_ref::version` (STRING), `mcp_deployment_ref::id` (STRING), `mcp_deployment_ref::name` (STRING), `mcp_deployment_ref::type` (STRING), and that the existing opaque `deployment_ref` (OBJECT) entry is still present
- [x] 4.2 Run `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.repository.PostgresTestSuiteEntityResolverTest"` and confirm all assertions pass

## 5. Functional Tests

- [x] 5.1 In `TestSuiteStructuredQueryFunctionalTests`, add `filtersByDeploymentRefName()`: create a suite with a known `deploymentRef` (name `"My App"`), execute a structured query against `test_suites` with `{"op":"eq","args":[{"type":"field","name":"deployment_ref::name"},{"type":"value","value_type":"string","value":"My App"}]}`, assert the suite is returned and suites with a different name (or no deployment ref) are not
- [x] 5.2 In `StructuredQueryExecuteFunctionalTests`, add `executesTestSuitesByDeploymentRefName()`: `POST /api/v1/queries/execute` with the same filter, assert HTTP 200 and the matching suite row is in the response
- [x] 5.3 Run `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteStructuredQueryTests"` and `"com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$StructuredQueryExecuteTests"` and confirm all pass

## 6. Schema Discovery Functional Test

- [x] 6.1 In `QuerySchemaDiscoveryFunctionalTests`, add a scenario asserting that `GET /api/v1/queries/entities/schema/test_suites` returns `deployment_ref::name` (typed `string`, source `deployment_ref`) and `mcp_deployment_ref::name` (typed `string`, source `mcp_deployment_ref`) among the schema fields, and that `deployment_ref` (typed `object`) is also still present
- [x] 6.2 Run `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$QuerySchemaDiscoveryTests"` and confirm it passes

## 7. Spec Sync

- [x] 7.1 Sync delta specs into main specs via `openspec sync --change test-suite-app-query-dsl` (or manually apply the delta changes from `openspec/changes/test-suite-app-query-dsl/specs/` into `openspec/specs/structured-query-model/spec.md` and `openspec/specs/query-schema-discovery/spec.md`)
