## 1. Shared detector (evaluation-runner-core)

- [x] 1.1 Create `runner.service.FilePlaceholderDetector` (`@Component @LogExecution`) with `boundFieldType(InputBindingDto, List<FieldDefinitionDto>)` and `isFileReference(String typeHint, InputBindingDto, List<FieldDefinitionDto>)` implementing the rule in design Decision 1 (explicit hint wins; no hint ⇒ bound column type `FILE`; null/empty schema, no `dataField`, `constantValue`-only or unknown field ⇒ false; hint trimmed, case-insensitive). Done: class compiles, Checkstyle clean.
- [x] 1.2 Add `FilePlaceholderDetectorTest` (unit) covering every scenario of the "Single FILE-placeholder decision rule" requirement (explicit `|string` on FILE column, `constantValue` only, null schema, unknown field, case-insensitive hint, hintless FILE column). Done: `./gradlew :evaluation-runner-core:test --tests "*FilePlaceholderDetectorTest"` passes.

## 2. Thread the schema through the resolvers (evaluation-runner-core)

- [x] 2.1 `TemplateContentResolver.resolveObject`: add `List<FieldDefinitionDto> testCaseSchema` param (recursive calls pass it through), replace the inline `typeHint` check with `FilePlaceholderDetector.isFileReference`; keep `resolveString` (interpolation) unchanged. Done: no remaining `SchemaFieldType.FILE.name().equalsIgnoreCase` in this class.
- [x] 2.2 `JsonataSourcePreprocessor.preprocess` / `resolveTypedValue` and `RequestBodyEvaluator.evaluate`: add the schema param and delegate the file decision to the detector (full/bare placeholders only; embedded stays stringify). Done: old signatures removed.
- [x] 2.3 `McpRequestResolver.resolve` / `resolveFileRef`: add the schema param (nullable) and delegate to the detector; `resolveWithVariables` (pure alias of `resolve`) is deleted. Done: old signatures removed.
- [x] 2.4 `RequestResolver.resolve` / `resolveForRun` / `resolveInternal`: add the schema param, pass it to JSON and `TEXT`-part resolution (URL-encoded values stay stringify-only); in `resolveMultipartBody` pass **no schema** (`null`) for `FormPartType.FILE` parts (design Decision 4). Done: old signatures removed.
- [x] 2.5 Update runner-core callers: `TurnLoopExecutor` (`requestResolver.resolveForRun(..., context.getSnapshotTestCaseSchema())`) and `EvaluationWorker` (`mcpRequestResolver.resolve(..., context.getSnapshotTestCaseSchema())`); fix compile errors in `eval-cli` if any. Done: `./gradlew :evaluation-runner-core:compileJava :eval-cli:compileJava` succeeds.

## 3. Root-app call sites and validator

- [x] 3.1 `ResolvedRequestService:93` and `TryItOutService` (`tryWithTestCase` deployment + MCP, `runChain`/`resolveForRun`): pass the dataset schema (`DatasetSchemaProvider.getSchema(suite.getDatasetId())`, loaded once per call); `tryWithVariables` passes the dataset schema too and keeps FILE-column bindings via `TryItOutVariableBindings.bindToFileColumns` (design Decision 5). Done: `./gradlew compileJava` succeeds.
- [x] 3.2 ~~`BindingValidator` `|file`-on-non-FILE-column warning~~ — dropped: explicit `|file` on a non-FILE column is a valid pattern (design Decision 3); `BindingValidator` unchanged.

## 4. Unit tests

- [x] 4.1 `TemplateContentResolverTest` and `RequestResolverTest`: hintless placeholder bound to FILE column → `files/...` (JSON structural body, nested); hintless FILE-bound placeholder in a URL-encoded value stays raw (stringify-only, same as explicit `|file`); hintless bound to STRING column unchanged; embedded placeholder unchanged; `|string` on FILE column unchanged; multipart `FILE` part with hintless FILE-bound placeholder keeps raw `@ef/...`; multipart `TEXT` part converts. Done: `./gradlew :evaluation-runner-core:test --tests "*TemplateContentResolverTest" --tests "*RequestResolverTest"` passes.
- [x] 4.2 `JsonataSourcePreprocessorTest` and `RequestBodyEvaluatorTest`: hintless full-value and bare placeholder bound to FILE column converted in `jsonataContent`; embedded placeholder not converted; null schema ⇒ explicit hint only. Done: both classes pass.
- [x] 4.3 `McpRequestResolverTest`: hintless FILE-bound placeholder converted; STRING-bound unchanged; no-binding direct-name lookup unchanged; null schema + `|file` still converted. Done: class passes.
- [x] 4.4 Add a validation test (`SuiteValidationServiceTest`) that `${{attachment|file}}` bound to a STRING column and a hintless placeholder bound to a FILE column both produce no warning (suite stays valid). Done: class passes.
- [x] 4.5 `TryItOutServiceTest` and `ResolvedRequestServiceTest`: schema reaches the resolvers for `tryWithTestCase` (deployment + MCP) and preview; `tryWithVariables` (HTTP single, chain, MCP) binds a suite FILE-column variable through the column and schema (`TryItOutVariableBindingsTest` covers `bindToFileColumns`). Done: classes pass.

## 5. Functional test and verification

- [x] 5.1 Add a `@PostgresFunctionalTests` case (extend `TryItOutFunctionalTests` or the run functional test that already stubs DIAL Core/`DialFileClient`): dataset with a FILE column + suite template `"url": "${{file}}"` bound to it; resolve/try-out with a test case holding `@ef/datasets/{id}/x.pdf` and assert the outgoing request contains `files/<bucket>/datasets/{id}/x.pdf`; assert the suite validates with no warnings. Done: test boots the app context and passes (`./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$<Nested>"`).
- [x] 5.3 Run `./gradlew spotlessApply checkstyleMain checkstyleTest :evaluation-runner-core:test :eval-cli:test` and the unit tests touched above; run `LayeredArchitectureTest` and `RunnerModuleConstraintsTest`. Done: all green (report any failure verbatim).

## 6. Docs and specs

- [x] 6.1 Update `docs/patterns/jsonata-evaluation-seam.md` and `docs/patterns/dial-file-storage.md` with the schema-type rule, `FilePlaceholderDetector`, the multipart FILE-part exclusion, and the try-out-with-variables column binding; add the new class to `docs/key-packages.md` if it lists `runner.service` classes. Done: docs mention the rule and exceptions.
- [x] 6.2 Sync delta specs into `openspec/specs/` (`dial-file-ref`, `mcp-tool-invocation`, `request-template`; `file-ref-validation` is unaffected, its FILE form-part skip is consistent) via the archive/sync step and flip `Status` to Implemented for the new requirements; also amend the `openspec/specs/try-it-out/spec.md` "Try it out with variables" sentence directly (variable bound to a FILE column becomes a `dataField` binding, design Decision 5; no delta); do **not** touch `openspec/specs/README.md` (no new spec folder, summaries still accurate), `config.yaml` or `AGENTS.md` (feature follows existing patterns; new injectable in an existing package). Done: main specs reflect the behaviour.
