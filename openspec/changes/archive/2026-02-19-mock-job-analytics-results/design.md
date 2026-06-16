## Context

The `TestSuiteEvaluationJob` is a mock implementation of the future K8s-based evaluation job. Currently it only simulates status transitions (PENDING → RUNNING → COMPLETED/FAILED) with a random sleep and optional random failure. The analytics storage (`test_case_run_results` table, `TestCaseRunResultRepository`) is fully implemented but never written to by the mock job, making the analytics API effectively empty for FE/client integration purposes.

The mock must write one `TestCaseRunResult` per enabled+valid test case × run index (from `runConfig.numberOfRuns`) to the analytics DB on successful completion, using realistic-looking request/response bodies derived from the suite's template configuration.

## Goals / Non-Goals

**Goals:**
- Write per-test-case `TestCaseRunResult` rows to analytics storage when mock job completes successfully
- Build a realistic `requestBody` from `TestSuite.requestTemplate` + `inputBindings` applied over `TestCase.data` (respecting per-case overrides) — reusable in the real eval job later
- Generate structured dummy `responseBody` per execution status (SUCCESS echo, ERROR/FAILED error envelope)
- Support `numberOfRuns` from `runConfig` (multiple runIndex values per test case)
- Only process enabled+valid test cases via a dedicated repository query
- Keep mock result generation non-fatal (run still completes even if generation fails)
- Paginate test case reads to handle large suites without OOM

**Non-Goals:**
- Actual HTTP calls to the target endpoint — this is a mock only
- Real metric computation
- Tracing / correlation ID propagation in mock results
- Streaming/progressive result writing (all at end of run, per page-batch)
- Changing the analytics write API (no new endpoints)

## Decisions

### Decision 1: Component split — four dedicated classes

**Chosen**: `MockRequestBodyBuilder`, `MockResponseBodyBuilder`, `MockResultsGenerator`, `MockResultsBatchWriter`

Each has a single responsibility:
- `MockRequestBodyBuilder` — resolves `requestTemplate.body` + `inputBindings` + `testCase.data` into a JSON string
- `MockResponseBodyBuilder` — builds a status-specific dummy response JSON string
- `MockResultsGenerator` — orchestrates paginated iteration and delegates to builders and batch writer
- `MockResultsBatchWriter` — owns the `@Transactional("analyticsTransactionManager")` boundary for each page-batch

**Why not put it all in `TestSuiteEvaluationJob`**: the job is already responsible for lifecycle/status management; mixing result generation logic violates SRP and makes the job hard to test in isolation.

**Why `MockResultsBatchWriter` separate from `MockResultsGenerator`**: Spring `@Transactional` only intercepts calls through the proxy. If `MockResultsGenerator` called its own `@Transactional` method internally, the transaction would be skipped. A separate injectable component avoids this self-invocation problem cleanly.

**Why `MockRequestBodyBuilder` reusable**: the real evaluation job will need to construct the same request body from the same template+bindings structure. Extracting it now avoids duplication later.

### Decision 2: Transaction strategy — one analytics tx per page-batch

**Chosen**: `MockResultsGenerator.generateAndSave()` is NOT `@Transactional`. It loops pages; `MockResultsBatchWriter.save(List<TestCaseRunResult>)` is `@Transactional("analyticsTransactionManager")`.

**Why not one giant analytics tx**: for a suite with 1000 test cases × 5 runs = 5000 rows, one open transaction can hold locks for seconds. Batching by page (100 test cases ≈ 500 rows per tx) is safer and recoverable on partial failure (analytics uses `INSERT ... ON CONFLICT DO NOTHING` so duplicate writes are safe).

**Why not meta-tx for reads**: test case reads happen on meta datasource; analytics writes on analytics datasource. These are separate connection pools. Meta reads do not need an explicit transaction — they are point-in-time reads on already-committed data.

### Decision 3: Dedicated repository method for enabled+valid test case pagination

**Chosen**: Add `List<TestCase> findEnabledValidByTestSuiteId(UUID testSuiteId, int offset, int limit)` to `TestCaseRepository` with a straightforward `WHERE test_suite_id = :testSuiteId AND is_enabled = true AND is_valid = true ORDER BY created_at_ms ASC, id ASC LIMIT :limit OFFSET :offset` implementation. `MockResultsGenerator` calls this in a page loop.

**Why a new repo method instead of `findAllByTestSuiteId` + programmatic `FilterCondition`**: The `FilterCondition`/`WhereBuilder` API was designed for HTTP query parameter parsing — it requires a `rawValue` string that gets parsed per `FilterFieldType`. Constructing `FilterCondition` objects programmatically couples the generator to internal string-parsing conventions and adds unnecessary complexity (sort/page wrappers, unused total-count flag). A dedicated method with a simple SQL WHERE clause is cleaner, self-documenting, and directly reusable by the real evaluation job later.

**Why not `findBatchByTestSuiteId` + in-memory filter**: that method doesn't accept filters and would load disabled/invalid cases unnecessarily.

**Why this is not a test-only method**: The mock job is production code, and the real K8s evaluation job will need the same query. AGENTS.md discourages test-only repository methods, but this is a legitimate data concern owned by the repository.

### Decision 4: Request body template resolution

**Algorithm in `MockRequestBodyBuilder` (tree-based, not string-based):**

String-based regex replacement is **not viable** because placeholder values can be JSON arrays or objects (e.g., `"messages": "${{messages}}"` where `messages` is `[{"role":"user","content":"Hello"}]`). Naive string replacement inside a JSON string literal produces broken JSON. The algorithm must therefore walk the JSON tree.

1. Effective template = `testCase.requestTemplateOverride` ?: `testSuite.requestTemplate` (may be null) — parse from JSON string
2. Effective bindings = `testCase.inputBindingsOverride` ?: `testSuite.inputBindings` (may be null/empty) — parse from JSON string
3. Parse `testCase.data` as `JsonNode`; build a **resolution map** `Map<String, JsonNode>` from bindings:
   - If binding has `dataField` → `map.put(templateVariable, testCaseData.get(dataField))`
   - If binding has `constantValue` → `map.put(templateVariable, TextNode(constantValue))`
4. Parse `template.body` as `JsonNode`; **walk the tree recursively**:
   - For each **text node** whose entire value matches a single placeholder `${{varName}}` or `${{varName:default}}`:
     - If `varName` exists in the resolution map → **replace the node itself** with the resolved `JsonNode` (preserving type: array stays array, object stays object, string stays string)
     - If not in map but default is present → replace node with `TextNode(default)`
     - If not in map and no default → leave as-is
   - For each **text node** containing mixed text + placeholder(s) (e.g., `"Hello ${{name}}, welcome"`):
     - Do string substitution using `resolvedValue.asText()` (scalar coercion) for each placeholder
     - Non-scalar resolved values (arrays/objects) are substituted as their JSON string representation
5. Serialize the mutated tree → return as JSON string

**Fallback**: if template or bindings are null/empty or parsing fails → return `testCase.data` as-is with a WARN log. Never throw; mock job must not fail due to template resolution.

### Decision 5: Response body shape

| Status        | HTTP code | Response body                                                                   |
|---------------|-----------|---------------------------------------------------------------------------------|
| `SUCCESS`     | 200       | `{"id":"mock-...","choices":[{"message":{"content":"Mocked answer."}}]}`        |
| `FAILED`      | 422       | `{"error":{"code":"MOCK_EVAL_FAILED","message":"Test case evaluation failed"}}` |
| `ERROR`       | 500       | `{"error":{"code":"MOCK_INTERNAL_ERROR","message":"Internal evaluation error"}}` |
| `TIMEOUT`     | 504       | `{"error":{"code":"MOCK_TIMEOUT","message":"Execution timed out"}}`             |

SUCCESS body intentionally echoes a chat-completions-style envelope so FE can test parsing real API shapes.

### Decision 6: Per-result status distribution

`testSuiteRunProperties.mockJob.resultFailureProbability` (default 0.10) controls the fraction of results marked `ERROR` (random per test case × runIndex). All others are `SUCCESS`. `FAILED` and `TIMEOUT` are not randomly generated in the mock — they are reserved for real execution semantics.

Rationale: a simple binary SUCCESS/ERROR is sufficient to test FE handling of mixed results without complicating the mock.

### Decision 7: Non-fatal generation failure

If `MockResultsGenerator.generateAndSave()` throws, `TestSuiteEvaluationJob` catches the exception, logs a WARN (with runId and exception), and continues to mark the run COMPLETED. The FE will see a completed run with zero analytics results, which is acceptable for a mock.

After the `generateAndSave` call (success or caught failure), `now` is refreshed (`now = System.currentTimeMillis()`) before `updateToCompleted` so the `completedAt` timestamp reflects actual completion rather than the pre-generation snapshot.

### Decision 8: runConfig parsing

`TestSuiteRun.runConfig` is stored as JSON. `MockResultsGenerator` parses it with `ObjectMapper` into `RunConfigDto` to get `numberOfRuns`. On parse failure or null `runConfig`, defaults to `numberOfRuns = 1` with a WARN log.

## Risks / Trade-offs

**[Risk] Large suites × large numberOfRuns → slow mock completion** → Mitigation: page-batch transaction size is bounded at 100 test cases × numberOfRuns; total time grows linearly. For the mock use case this is acceptable. Document in config that `max-duration-ms` should be increased for large suites.

**[Risk] Template tree-walk misses deeply nested placeholders** → Mitigation: recursive walk visits all text nodes regardless of depth; regex `\$\{\{([^}]+)\}\}` is only used to detect/extract placeholder tokens within individual text node values, not for global string replacement.

**[Risk] MockRequestBodyBuilder used beyond mock context before cleanup** → Mitigation: keep it in `.service.domain.job` package (not a shared utility), make the Javadoc comment explicit that it is mock-only until the real job reuses it.

**[Risk] Analytics table grows with mock data** → Mitigation: no mitigation needed — `ON CONFLICT DO NOTHING` prevents duplicates on re-runs; analytics table is append-only by design and has no TTL concerns in dev/test.

## Migration Plan

No DB migrations. No API changes. Configuration defaults are backward-compatible (new `result-failure-probability` key with default in `application.yml`).

Rollout: deploy as-is; mock job immediately starts writing results. No flag needed.

Removal: when real K8s eval job is implemented, delete `MockResultsGenerator`, `MockResultsBatchWriter`, `MockRequestBodyBuilder`, `MockResponseBodyBuilder`, and the call site in `TestSuiteEvaluationJob`. Keep `MockRequestBodyBuilder` only if it is generalised for real job use before that point.

## Open Questions

- None — all decisions resolved during design.
