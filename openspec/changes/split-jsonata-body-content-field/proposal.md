# Split JSONata body source out of `content` into `jsonataContent`

## Why

The `jsonata-request-templates` change (archived `2026-07-31`, **not yet merged to `development`** — no released API consumer, and FE has not started integration) overloaded `JsonRequestBodyDto.content` as `Object`: a `Map<String, Object>` (legacy structural template) *or* a `String` (JSONata source). That union types poorly in the OpenAPI contract (`oneOf = {Map, String}` on a single property renders as untyped `Object`/`any` in Swagger and generated clients), forces `instanceof` type-sniffing at every touch point, and makes persisted suite JSON non-self-describing. Splitting the union into two explicitly named, typed fields is a pre-release contract fix — the window to do it painlessly closes at merge.

## What Changes

- **BREAKING (pre-release only)**: `JsonRequestBodyDto.content` reverts to `Map<String, Object>` (legacy structural template only). String values are no longer accepted in `content`.
- New field `JsonRequestBodyDto.jsonataContent` (`String`, nullable): the JSONata source body. Exactly the same evaluation semantics as today's String-typed `content` — `${{}}` preprocessing via `JsonataSourcePreprocessor`, direct JSONata evaluation, must-evaluate-to-object contract, write-time neutralize-and-validate (400 on syntax error), `REQUEST_BODY_EVALUATION_ERROR` handling. Only the carrier field changes.
- New write-time validation: `content` and `jsonataContent` both non-null → HTTP 400 (mutual exclusivity). Both null → no body (unchanged from `content: null` today).
- `RequestBodyEvaluator` branches on which field is set instead of `instanceof`; the "unsupported content type" runtime error path is retired (compile-time typing).
- `TemplateVariableExtractor` explicitly scans `jsonataContent` for `${{}}` placeholders (today the String rides the generic `extractFromObject(Object)` path — with the split this must be an explicit call, otherwise binding validation silently breaks for JSONata suites).
- OpenAPI: `@Schema` on the two typed fields replaces the `oneOf = {Map, String}` annotation; the three `*-jsonata-body` example files move the JSONata source from `content` to `jsonataContent`.
- No evaluation-pipeline change: `JsonataSourcePreprocessor`, `JsonataEvaluationService`, frame bindings/`$history`, multi-turn turn loop, streaming, response columns, and try-it-out warning downgrade are all untouched.
- No DB migration: `request_template` is opaque JSONB (whole-DTO Jackson round-trip). Any dev-environment suite rows already persisted with String `content` on this branch need a one-off data fix or reset (deserialization into the Map-typed field fails at read time).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `request-template`: the `application/json` body contract changes from "`content` is `Object` — `Map` or `String`" to "`content` is `Map` (legacy structural), `jsonataContent` is `String` (JSONata source), mutually exclusive (400 when both set)". Affected requirement blocks: the `body` field definition, the JSON-body authoring scenarios, the unconditional-JSONata-evaluation requirement wording, write-time JSONata validation ("String-content" → `jsonataContent`), resolution step 12, and the seam paragraph. Evaluation semantics themselves are unchanged.

## Impact

- **API (pre-release)**: `POST/PUT /api/v1/test-suites` request/response shape for `requestTemplate.body` (`application/json` only); multipart/urlencoded bodies untouched. FE not started — coordinate the handoff on the new shape.
- **Code — `evaluation-runner-core`**: `runner.dto.JsonRequestBodyDto` (field split), `runner.service.RequestBodyEvaluator` (field-presence dispatch, typed signature), `runner.service.RequestResolver.resolveJsonBody` (pass both fields / the DTO).
- **Code — EF backend**: `service.domain.TestSuiteRequestValidator.validateRequestTemplateBody` (rewire to `jsonataContent` + add both-set 400), `service.domain.TemplateVariableExtractor.extractFromBody` (explicit `jsonataContent` scan).
- **Tests (~10–15 files, mostly mechanical `.content(str)` → `.jsonataContent(str)`)**: `RequestResolverTest`, `RequestBodyDtoSerializationTest`, `TestSuiteRequestValidatorTest`, `TemplateVariableExtractorTest`, `TurnLoopExecutorTest`, `JsonataRequestTemplateFunctionalTests`, `MultiTurn*` functional tests, `TryItOutFunctionalTests`, plus new mutual-exclusivity cases.
- **Docs**: `openspec/specs/request-template/spec.md` delta, AGENTS.md "Request-template JSONata evaluation seam" bullet, three `openapi/examples/*-jsonata-body.json` files. No `docs/configuration.md` change (no config properties involved). No Flyway migration.
- **Risk**: low — single-branch, pre-merge. Main failure mode to guard in review/tests: forgetting the explicit `TemplateVariableExtractor` scan of the new field (silent loss of variable extraction → false "unbound variable" / orphan-binding validation results).
