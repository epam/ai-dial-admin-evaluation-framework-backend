## Why

Multi-turn test cases and multi-request chains are supported only for `DEPLOYMENT` HTTP suites. `MCP_TOOL` suites are rejected outright: a run creation against a dataset holding any multi-turn case returns 409, try-it-out returns 409, the CLI fails pre-flight, and a non-empty `additionalRequests` on an MCP suite is a write-time 400.

Nothing in the MCP protocol, DIAL Core's MCP proxy, or the Java MCP SDK causes this. The execution machinery is already transport-neutral — turn planning, per-turn binding detection, frame accumulation, response-column extraction, row identity stamping, and chain fail-fast all work without knowing how a request is issued. Two gaps in our own code produced the rejections:

1. `ArgumentTemplateDto` carries only `arguments` (`Map<String, Object>` with `${{}}` placeholders) — the structural equivalent of `JsonRequestBodyDto.content`. It never gained the raw-JSONata sibling of `jsonataContent`, and only the raw form can reference frame variables, because a structural JSON map cannot express an unquoted `$history`. Without it, an MCP call cannot consume anything a previous turn or request returned.
2. `TurnLoopExecutor.runOneTurn` and `RequestExecutionSpec` are HTTP-shaped (URL building, header assembly, body serialization, HTTP method), so the loop had no seam through which a tool call could be issued.

The result is an arbitrary capability cliff between the two suite types, and a parallel MCP execution branch in `EvaluationWorker` that duplicates retry/backoff, response truncation, and result-row construction while lagging the HTTP path on turn/request identity and accumulated columns.

## What Changes

- **MCP suites support multi-turn test cases.** A `perTurn` binding drives `N = multiTurnData.length` tool invocations per test-case repetition, sequential and fail-fast, each resolving its arguments from `merge(shared, multiTurnData[i])` — identical turn semantics to HTTP suites. The three rejection guards (run creation, try-it-out, CLI pre-flight) are removed.
- **MCP suites support request chains.** `additionalRequests` becomes valid on an `MCP_TOOL` suite: request #0 is the suite's own `toolRef` + `argumentTemplate`, and entries 1..N-1 each carry their own `toolRef`, `argumentTemplate`, `responseColumns`, `inputBindings`, and optional `name`. The existing chain-length cap (`MAX_ADDITIONAL_REQUESTS` = 10) and suite-wide response-column union cap apply unchanged. The write-time 400 is removed.
- **Chains stay homogeneous.** A suite has exactly one target — `deploymentRef` or `mcpDeploymentRef` — so every entry in its chain must match the suite's `suiteType`. Mixed HTTP/MCP chains are rejected at create, update, and clone. `suiteType` remains the sole discriminator; entry field population is validated against it rather than used to derive a per-entry type.
- **`RequestDefinitionDto` gains two nullable fields**, `toolRef` and `argumentTemplate`, alongside the existing `endpointRef` and `requestTemplate`. One DTO, reusing the existing `additional_requests` JSONB column — mirroring how `test_suites` itself holds both field sets as nullable columns and discriminates on `suite_type`. No migration.
- **`ArgumentTemplateDto` gains `jsonataArguments`** (`String`, mutually exclusive with `arguments`), routed through the existing `RequestBodyEvaluator` so argument templates gain frame bindings — the previous turn's and every earlier request's extracted response columns, bound by name. This is what makes id, cursor, token, and accumulated-context threading expressible for tool calls. Both forms converge on one evaluation path, exactly as `content`/`jsonataContent` already do.
- **MCP rows join the turn and request dimensions.** `turn_index`/`total_turns` and `request_index`/`total_requests` are stamped on MCP result rows and eval summaries under the same rules as HTTP rows (explicit values only when `N > 1` / chain length > 1, so a single-shot MCP suite's rows stay byte-identical to today's). Conditional metrics gain the `turn` and `request` namespaces on MCP rows with no change to `ConditionExpressionEvaluator`.
- **The executor gains one invocation seam.** A resolve-and-invoke abstraction replaces the HTTP-only tail of `TurnLoopExecutor.runOneTurn`; `RequestChainExecutor` selects the implementation once per chain from `suiteType`. `EvaluationWorker`'s MCP branch and its duplicated retry/truncation/row-building code are deleted, and `McpRequestResolver` is retired in favour of the shared resolution path.
- **No breaking changes.** Every addition is optional and nullable; existing MCP and DEPLOYMENT suites, stored snapshots, and persisted rows are unaffected.

## Capabilities

### New Capabilities

None. This change extends existing capabilities rather than introducing a new one.

### Modified Capabilities

- `multi-turn-test-case`: Removes the "MCP suites reject multi-turn datasets" requirement; multi-turn becomes transport-independent, driven by per-turn bindings for both suite types.
- `multi-request-suite`: Removes the "MCP suites reject additional requests" requirement and the "MCP path is untouched" / "MCP rows never stamp request columns" scenarios; adds chain homogeneity (every entry's shape matches `suiteType`) and the MCP shape of a chain entry.
- `mcp-tool-invocation`: Argument resolution moves from `McpRequestResolver`'s `${{}}`-only substitution to the shared JSONata evaluation seam, adding `jsonataArguments` and frame bindings; adds per-request `toolRef` resolution within a chain.
- `eval-execution-engine`: Replaces the `EvaluationWorker` MCP branch with a per-chain invocation seam; MCP execution flows through the unified turn loop and chain executor and emits one row per `(request_index, turn_index)`.
- `suite-run-snapshot`: An `MCP_TOOL` snapshot may now carry a non-empty `additionalRequests` (currently required to be empty); `argumentTemplate` and `toolRef` become per-request within the chain.
- `test-suites`: Create/update validation accepts `additionalRequests` on `MCP_TOOL` suites and enforces per-entry shape agreement with `suiteType`.
- `try-it-out`: Removes the MCP + multi-turn 409; MCP try-it-out executes the turn sequence and returns per-turn `history` like the DEPLOYMENT path.
- `eval-cli`: Removes the "MCP Suites Reject Multi-Turn Test Cases Pre-Flight" requirement so the CLI matches backend behaviour.
- `request-template`: The JSONata evaluation seam (frame bindings, object-result contract, reserved binding names, evaluation bounds) is stated as serving argument templates as well as request-template bodies.
- `test-suite-clone`: Clone carries an MCP suite's chain and rewrites suite-scoped DIAL file references inside each entry's `argumentTemplate`, not only the suite-level one.

## Impact

**API** — `POST`/`PUT /api/v1/test-suites` and the clone endpoint accept `additionalRequests` on `MCP_TOOL` suites; `RequestDefinitionDto` gains `toolRef` and `argumentTemplate`; `ArgumentTemplateDto` gains `jsonataArguments`. New 400s: mixed-shape entry, both/neither field set populated on an entry, both `arguments` and `jsonataArguments` set, invalid `jsonataArguments` syntax. Removed 409s: MCP + multi-turn at run creation and try-it-out. OpenAPI examples for MCP chains and JSONata argument templates must be added.

**Database** — none. `additional_requests` (meta) and `turn_index`/`total_turns`/`request_index`/`total_requests` (analytics, V1.13/V1.14/V1.17/V1.18) already exist and are transport-neutral. No migration, no jOOQ regeneration.

**Configuration** — none. No new properties; existing `dial.mcp.*` timeouts and evaluation retry/limit properties apply per invocation as they do today.

**`evaluation-runner-core`** — the invocation seam and its HTTP and MCP implementations, generalized `RequestExecutionSpec`, `TurnLoopExecutor`, and `RequestChainExecutor`; `McpRequestResolver` removed. Module boundary contract unchanged (no JDBC/jOOQ, no dependency on the EF backend).

**Main app** — `TestSuiteRequestValidator`, `SuiteValidationService`, `TemplateVariableService`, `TemplateVariableExtractor`, `SuiteSnapshotBuilder` (stale comment), `TestSuiteRunService` and `TryItOutService` (guard removal, MCP turn sequence), `TestSuiteCloneService`. `TestCaseRepository.existsMultiTurnByDatasetId` and `TestCaseService.datasetHasMultiTurnCases` become unused and are removed.

**`eval-cli`** — `RunOrchestrationService` pre-flight guard removed; MCP suites execute through the same chain and turn loop.

**Docs** — `docs/patterns/multi-turn-test-cases.md`, `multi-request-suites.md`, `mcp-tool-invocation.md`, `jsonata-evaluation-seam.md`.

**Known limitation retained** — `McpToolInvoker` creates, initializes, and closes an `McpSyncClient` per call, so each turn and each chain position is a separate MCP session. Server-side session continuity is out of scope; state must be threaded explicitly through arguments via the frame, which is the same contract HTTP suites have.
