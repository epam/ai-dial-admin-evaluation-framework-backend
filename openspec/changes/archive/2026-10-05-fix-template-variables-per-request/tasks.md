## 1. Service — per-request chain extraction

- [x] 1.1 In `service/domain/TemplateVariableService.java` add private `buildChainVariables(suite, testCaseSchema, data)` returning `Map<Integer, List<TemplateVariableDto>>` (LinkedHashMap, chain order): `MCP_TOOL` → `{0: resolveMcpVariables(...)}`; `DEPLOYMENT` → `0` from suite's own template/bindings/endpoint, `i+1` from `additionalRequests[i]`'s own template/bindings/endpoint, every index present (design D2/D3). Done: `./gradlew compileJava` passes.
- [x] 1.2 Change `getTemplateVariables` and `getTestCaseTemplateVariables` return type to `Map<Integer, List<TemplateVariableDto>>`, keep existing 404 / unbound-suite guards, delegate the final branch to `buildChainVariables`; update class javadoc. Done: compiles.
- [x] 1.3 Update `TemplateVariableServiceTest` for the map shape and add cases: chain returns keys `0..N` in order; request 1 uses own bindings (constant) and own endpoint type (`NUMBER`); null/placeholder-free request → `[]`; no-chain suite and MCP suite → single key `0`; MCP suite whose persisted `additionalRequests` is non-empty (built directly in the test, bypassing write-time rejection) still → only key `0`; test-case level resolves request 1 `dataField` from test case `data`. Done: `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.TemplateVariableServiceTest"` green.

## 2. Controller & OpenAPI

- [x] 2.1 In `web/controller/TemplateVariableController.java` return `Map<Integer, List<TemplateVariableDto>>` from both endpoints; replace content with `@Content(mediaType = "application/json", schema = @Schema(type = "object", …), additionalPropertiesArraySchema = @ArraySchema(schema = @Schema(implementation = TemplateVariableDto.class)))` (design D5); update both operation descriptions to the current shape only (keys = request index, `"0"` = suite's own request, `"n"` = `additionalRequests[n-1]`, all indices present, chain order) — no BREAKING/changelog wording. Done: compiles.
- [x] 2.2 Rewrite the 4 examples `src/main/resources/openapi/examples/api-v1-test-suites{,-test-cases}-template-variables-GET-response-200-{minimal,full}.json`: `minimal` = `{"0": [...]}`, `full` = two-request chain (`"0": []`, `"1": [user_message, temperature]`). Done: files rewritten; verified by 3.3.

## 3. Functional tests

- [x] 3.1 Update every functional assertion that treats either endpoint's response as an array (`TemplateVariableFunctionalTests`, `McpTryItOutFunctionalTests:164` — confirm with `grep -rn "template-variables" src/test`) to read the `"0"` entry; fix the stale "per-test-case template-variables endpoint was removed" comments at `TestCaseConvenienceApiFunctionalTests.java:45` and `McpTryItOutFunctionalTests.java:178`. Done: those classes compile.
- [x] 3.2 Add GH #217 repro to `TemplateVariableFunctionalTests`: suite with request #0 `GET /settings` (no placeholders) and `additionalRequests[0]` `POST /chat/completions` with `${{user_message}}` and `${{temperature}}` → suite endpoint returns `{"0": [], "1": [user_message, temperature]}`; assert keys as an ordered list `["0","1"]`; test-case endpoint resolves `user_message` under `"1"` from test case data. Done: test exists.
- [x] 3.3 Add a `/v3/api-docs` assertion to `TemplateVariableFunctionalTests`: for both template-variables operations the 200 `application/json` schema is `type: object` with array `additionalProperties` referencing `TemplateVariableDto`, and the `minimal`/`full` examples are present. Done: test exists.
- [x] 3.4 Run the affected functional suites: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TemplateVariableTests" --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$McpTryItOutTests"` plus any other nested class touched in 3.1. Done: all green.

## 4. Docs, formatting, verification

- [x] 4.1 Add one line to `docs/patterns/multi-request-suites.md` ("Try-out coverage") stating both template-variables endpoints return the whole chain keyed by request index, each request resolved from its own template/bindings/endpoint, and that map keys use the same index semantics as `resolved-request`'s `requestIndex` (0 = suite request, n = `additionalRequests[n-1]`). Done: line present.
- [x] 4.2 `./gradlew spotlessApply checkstyleMain checkstyleTest` clean, then full `./gradlew build` once. Done: build green.
- [x] 4.3 Generate FE handoff via `fe-api-handoff` skill covering the breaking response-shape change. Done: handoff file path reported.
