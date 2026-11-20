## 1. Shared streaming response semantics

- [x] 1.1 Update `TryItOutService.readStream` to parse the existing accumulator output into a JSON value for `TryItOutCoreResponseDto.body`, falling back to a typed events envelope when the assembled output is absent; retain parsed events/status/truncation metadata and the same extraction document. Done when focused `TryItOutServiceTest` assertions show a real assembled object in body, independent original events, one stream parse and existing resource closure.
- [x] 1.2 Extend `TryItOutServiceTest` for chat-completions (including synthetic custom_content/custom_fields), Responses API and Anthropic assembly, structured/empty fallback, JSON typing and explicit null preservation; done when targeted tests assert concrete expected bodies/events and serialization preserves types without double encoding.
- [x] 1.3 Update/add `TryItOutServiceTest` read-error, idle/absolute timeout and size-limit cases; done when targeted tests verify evaluation-equivalent partial/fallback body, retained dispatched events/status/warning, skipped extraction and unchanged fail-fast behaviour.

## 2. Endpoint and invocation-history coverage

- [x] 2.1 Update/add `TryItOutFunctionalTests` coverage for both test-case and variables endpoints, including DEPLOYMENT and ROUTE SSE responses; done when the registered `PostgresFunctionalTests` try-out nested suite verifies assembled JSON in body and ordered parsed events on the wire, with non-SSE bodies and omitted SSE fields unchanged.
- [x] 2.2 Cover multi-turn, multi-request and combined/mixed streaming histories in `TryItOutServiceTest` and relevant `TryItOutFunctionalTests`; done when executed targeted tests verify per-invocation body/events isolation, top-level equality with the last history response, early-failure executed-prefix retention and unchanged extraction/frame propagation from the exposed assembled document.
- [x] 2.3 Verify existing MCP final-response behaviour using `McpTryItOutFunctionalTests`; done when its registered functional suite passes with final tool-result body preserved and streaming/events absent for both endpoints, without SDK transport changes.

## 3. Public API documentation

- [x] 3.1 Update `TryItOutCoreResponseDto` body/events schema descriptions and any affected controller descriptions; done when DTO/OpenAPI documentation explicitly distinguishes assembled body, parsed SSE events, partial-string/structured fallback and unchanged non-SSE/MCP responses without adding a new field.
- [x] 3.2 Update both try-out SSE response JSON examples under `src/main/resources/openapi/examples` and their existing OpenAPI functional assertions; done when `/v3/api-docs` tests verify examples are attached to both registered operations, show recognized-provider assembled body plus events, and expose corrected schema descriptions.

## 4. Spec synchronization and final verification

- [x] 4.1 Synchronize this change's `try-it-out` delta into `openspec/specs/try-it-out/spec.md` via `/opsx:sync` after implementation; update implemented statuses/notes and directly correct the main Purpose's obsolete unconditional-envelope wording; done when all four changed requirements retain their existing scenarios, match implemented behaviour and `openspec validate --specs` passes. Do not overwrite older active-change artifacts.
- [x] 4.2 Update relevant try-out documentation in `docs/patterns` and `openspec/specs/README.md` if its current summary promises an unconditional SSE envelope; done when those delivered docs describe body/events correctly and the spec index remains accurate. No config, database or project-wide convention changes are planned.
- [x] 4.3 Complete independent code review/spec-drift checks and final `./gradlew clean build` per project workflow; done when findings are resolved or explicitly dispositioned, required tests/Checkstyle/Spotless pass and actual command results are recorded before archive.

## Execution notes

- Groups 2 and 3 depend on group 1's corrected response contract and can proceed independently when their test-file ownership does not overlap. Group 4 follows implementation and review. One group and at most five tasks per implementation iteration.
- Compile while coding; run each group's targeted tests once at completion, affected tests after corrections and one final full build. Coordinate ownership of `TryItOutServiceTest` between groups 1 and 2.
- Preserve all unrelated existing working-tree changes, especially the previous custom-fields implementation. No commits/pushes or frontend work are included.
- The breaking rollout/consumer communication and rollback guidance are documented in `design.md`; executing external communication requires separate authorization.
- Verification: coordinator synced the delta and corrected the main Purpose; all four modified requirement blocks match the delta and retain their previous scenario names. `openspec validate --specs`: 79 passed, 0 failed; spec index: 79 folders, no missing or stale entries. Coordinator reports strict change validation passed and no config/architecture updates needed.
- Coordinator reports independent code review found no issues; the spec audit's pre-existing non-SSE null-omission mismatch was corrected in the specs/schema description without changing runtime behavior. Targeted service, try-out, MCP and API-doc tests passed, including affected correction reruns.
- Coordinator's final `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./gradlew clean build`: exit 0, `BUILD SUCCESSFUL in 6m 45s`, `41 actionable tasks: 41 executed`; root, CLI and core tests, Checkstyle and Spotless passed. Standard JVM class-sharing and Byte Buddy Unsafe deprecation warnings only.
