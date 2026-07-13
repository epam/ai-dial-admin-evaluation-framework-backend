## 1. DTO change

- [x] 1.1 Add optional `type` field to `DeploymentReferenceDto` after `version`: `@Size(max = 50)` + `@Schema(example = "dial-application", description = "Deployment type: dial-model or dial-application")`, no `@NotBlank`.
- [x] 1.2 Run `./gradlew spotlessApply` and confirm the module still compiles (`./gradlew compileJava`).

## 2. OpenAPI examples

- [x] 2.1 Add `"type": "dial-application"` (or `"dial-model"`) to the `deploymentRef` object in the full deployment-suite example JSONs under `src/main/resources/openapi/examples/`: `api-v1-test-suites-POST-request-full.json`, `api-v1-test-suites-POST-response-201-full.json`, `api-v1-test-suites-POST-request-subset.json`, `api-v1-test-suites-id-GET-response-200-full.json`, `api-v1-test-suites-id-PUT-request-full.json`, `api-v1-test-suites-id-PUT-response-200-full.json`, `api-v1-test-suites-id-clone-POST-request-full.json`, `api-v1-test-suites-id-clone-POST-response-201-full.json`, and `suiteSnapshot.deploymentRef` in `api-v1-test-suite-runs-id-GET-response-200-full.json`.
- [x] 2.2 Leave `api-v1-test-suites-POST-request-minimal.json` without `type` (demonstrates optionality); skip the `...-mcp.json` example (uses `mcpDeploymentRef`).

## 3. Tests

- [x] 3.1 Extend the test-suite CRUD functional test: create a DEPLOYMENT suite with `deploymentRef.type = "dial-application"`, GET it, assert `type` returned; and assert a suite created without `type` reads back `type = null`.
- [x] 3.2 Extend a run functional test: create a run from a suite with `deploymentRef.type = "dial-model"`, GET the run detail, assert `suiteSnapshot.deploymentRef.type = "dial-model"`.
- [x] 3.3 Run the affected functional tests and confirm they pass (`./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$<relevant nested tests>"`).

## 4. Docs

- [x] 4.1 If `docs/database-schema.md` enumerates the `test_suites.deployment_ref` / `suite_snapshot` JSONB field shape, add `type` to that field list. Otherwise no change.
