## Context

When a test suite run executes, the system re-reads the suite's mutable config (`request_template`, `input_bindings`, `deployment_ref`, `endpoint_ref`, MCP fields) from the database per test case. `ResolvedRequestService.resolveRequest()` calls `testSuiteRepository.findById()` on every invocation. If a user edits the suite during execution, subsequent test cases use the modified config — producing inconsistent results within a single run.

Current data flow:
```
TestSuiteRunService.createRun()     → snapshots only RunConfigDto
TestSuiteEvaluationJob              → reads suite from DB (once)
InProcessEvaluationExecutor         → reads suite from DB (once)
                                    → paginates test_cases with OFFSET/LIMIT (live)
EvaluationWorker.execute()          → calls ResolvedRequestService
  ResolvedRequestService            → reads suite from DB (per test case!) ← BUG
```

Additionally, test case *data* changes mid-run affect any TC not yet fetched from the paginated query, and the OFFSET/LIMIT pagination is susceptible to skip/duplicate anomalies if rows are inserted or deleted between pages.

## Goals / Non-Goals

**Goals:**
- Guarantee run-level consistency: every test case in a run uses the same suite configuration AND the same test case data
- Persist the suite configuration and test case data used for each run (enables V2: partial rerun, time-travel)
- Stabilize the test case set and its data: run operates on a fully snapshotted, immutable copy
- Expose suite snapshot in run detail API for future FE consumption

**Non-Goals:**
- Full test suite audit/versioning (change log over time, independent of runs)
- Partial rerun or time-travel rerun implementation — only the foundational snapshot is built here
- Modifying `test_case_run_results` schema

## Decisions

### D1: Persist snapshot as JSONB in `test_suite_runs` (not in-memory only)

**Choice:** Add `suite_snapshot JSONB` column to `test_suite_runs`.

**Alternatives considered:**
- *In-memory snapshot (read once, pass through)*: Fixes the consistency bug but doesn't persist. No V2 rerun support. JVM crash loses state.
- *Long-lived REPEATABLE READ transaction*: Clean consistency via PostgreSQL MVCC, but holds a DB connection for minutes during HTTP I/O. Not suitable given 20 max concurrent runs.
- *Separate snapshot table*: More normalized, but adds join complexity across dual datasources. Suite config is small and bounded — JSONB in the run row is simpler.

**Rationale:** Persistence is needed for V2 features (partial rerun, time-travel). Suite config is bounded in size (a handful of JSONB fields, not proportional to test case count). Single column avoids a new table and cleanup lifecycle.

### D2: Snapshot content — execution-relevant mutable fields only

The snapshot captures all suite fields that affect test case execution, plus a `snapshotVersion` marker for schema evolution:

```json
{
  "snapshotVersion": "1",
  "suiteType": "DEPLOYMENT",
  "deploymentRef": {...},
  "endpointRef": {...},
  "requestTemplate": {...},
  "inputBindings": [...],
  "responseColumns": [...],
  "testCaseSchema": [...],
  "mcpDeploymentRef": {...},
  "toolRef": {...},
  "argumentTemplate": {...}
}
```

**Excluded:** `id`, `name`, `description`, `createdBy`, `version`, `valid`, `validationWarnings`, timestamps — these are metadata, not execution config. `name` is already captured as `test_run_name` context.

**Note on `testCaseSchema`:** Not consumed by the executor at run time (validation happens at suite-save time). Retained for future partial-rerun validation and historical-run UI display.

**Format:** Stored as serialized JSONB using the existing DTO types. `SuiteSnapshotDto` is a typed wrapper. Serialization uses the shared `ObjectMapper`.

#### Schema evolution strategy

Historical snapshots must remain readable to support future rerun features.

- **Version marker**: `snapshotVersion` string (initial value `"1"`). Bumped on non-backward-compatible schema changes (rename, remove, semantic change).
- **Adding a field** (backward-compatible): no version bump; old snapshots deserialize with the new field null/defaulted.
- **Renaming or removing a field** (non-backward-compatible): bump `snapshotVersion`. Either provide a version-aware adapter or mark old-version snapshots as historical-only.
- **Reading an unknown/newer version** (e.g., after rollback): log a warning, mark the run as historical-only (not rerunnable with the current engine), return JSON as-is to API callers, refuse execution with error code `UNSUPPORTED_SNAPSHOT_VERSION` (category `INTERNAL`).
- **Missing version field**: default to `"1"` for backward compatibility.

### D3: Snapshot test case data in `test_case_run_inputs` table (meta DB)

**Choice:** Create a `test_case_run_inputs` table in the **meta DB** (not analytics) rather than adding a JSONB column to `test_suite_runs`. The table stores all execution-relevant per-test-case data for a run.

**Schema:**
```
test_case_run_inputs
  run_id                    VARCHAR(36)  NOT NULL  → FK test_suite_runs(id) CASCADE DELETE
  position                  INTEGER      NOT NULL  → zero-based order (snapshot sort order)
  test_case_id              VARCHAR(36)  NOT NULL
  test_case_name            VARCHAR(255) NOT NULL
  test_case_data            JSONB        NOT NULL
  request_template_override JSONB        NULL
  input_bindings_override   JSONB        NULL
  PRIMARY KEY (run_id, position)
```

**Rationale:**
- **Full data snapshot, not just IDs.** Captures `test_case_data` + per-case overrides. Eliminates test case data inconsistency during long runs — executor reads from this table, not from live `test_cases`.
- **Meta DB location** — co-located with the run record. Written within the same REPEATABLE READ snapshot transaction. Lifecycle tied to the run via FK + CASCADE DELETE (no separate cleanup needed).
- **Replaces `test_case_ids_snapshot` JSONB column** — the table is richer and supersedes the need for an ID-only column.

**Alternative considered:** `test_case_ids_snapshot JSONB` in `test_suite_runs` — snapshots only IDs, not data. Chosen against because test case data changes mid-run would still affect results. The table approach solves both ID stability and data consistency.

**Lifecycle and cleanup:** `test_case_run_inputs` is transient execution infrastructure. Its primary purpose ends when the run completes — `test_case_data` is durably preserved in `test_case_run_results` (analytics DB), and that copy is sufficient for V2 partial rerun. V2 rerun strategy is **replay** (resend the resolved `request_body` from analytics), not re-resolve from template + overrides, so `request_template_override` / `input_bindings_override` are not needed after execution completes. Cleanup is handled by two mechanisms:

1. **CASCADE DELETE** — when a `test_suite_runs` row is explicitly deleted, all associated `test_case_run_inputs` rows are removed automatically.
2. **Scheduled retention cleanup** (in scope) — a periodic `@Scheduled` job (`TestCaseRunInputsRetentionJob`) DELETEs `test_case_run_inputs` rows for runs in a terminal state (COMPLETED or FAILED) whose `updated_at` is older than the configured retention window. Default retention: **1 day**. Configuration property: `evaluation.run.inputs-retention-days` (default: `1`). This bounds meta DB growth without coupling cleanup to the hot execution path.

**Column selection tiers for `test_suite_runs`** (two tiers, `test_case_ids_snapshot` removed):

| Tier | Used by | Includes `suite_snapshot` |
|------|---------|---------------------------|
| `SELECT_LIST_COLUMNS` | `findAll` / paginated list queries | No |
| `SELECT_DETAIL_COLUMNS` | `findById` (GET /runs/{runId}) | Yes |

`test_case_run_inputs` rows are internal execution infrastructure and are not exposed via the API. The `TestSuiteRunRowMapper` uses `hasColumn()` for `suite_snapshot` to handle list vs. detail queries.

### D4: Snapshot phase at start of `executeRunAsync()` under REPEATABLE READ

**Choice:** The snapshot phase (read suite + all TC data → persist both snapshots) runs at the **start of `executeRunAsync()`**, using a `TransactionTemplate` with `ISOLATION_REPEATABLE_READ`. `createRun()` remains a fast, short transaction that only validates, counts TCs, and saves the PENDING run record.

**Why not in `createRun()`?**
Reading and persisting 50K TCs within a synchronous API call would make `POST /runs` take 10–15 seconds. Moving it to the async phase keeps the API response immediate (202 Accepted).

**Snapshot transaction flow:**
```
executeRunAsync():
  snapshotTx = TransactionTemplate(metaTransactionManager, ISOLATION_REPEATABLE_READ)
  snapshotTx.execute {
    suite = testSuiteRepository.findById(runId)         ← consistent snapshot
    suiteSnapshot = suiteSnapshotBuilder.build(suite)
    page through test cases (findEnabledValidByTestSuiteId, paginated) {
      INSERT batch into test_case_run_inputs             ← within same tx
    }
    testSuiteRunRepository.updateSuiteSnapshot(runId, suiteSnapshot)
  }
  // Connection released — duration: ~10–15s pure DB I/O for 50K TCs

  repository.updateToRunning(runId, ...)
  context = buildContextFromPersistedSnapshot(run)
  evaluationExecutor.execute(context)
```

**REPEATABLE READ duration:** bounded by DB I/O only (reads + batch INSERTs). No HTTP call occurs during this window. For 50K TCs at 100/page: ~500 reads + ~500 batch INSERTs ≈ 10–15 seconds. Meta DB write volume during this window is low (user edits only) so VACUUM impact is negligible.

**`suite_snapshot` update:** Written as an UPDATE to the PENDING run record within the snapshot transaction. Immediately after the batch insert into `test_case_run_inputs`, the same transaction SHALL also UPDATE `test_suite_runs.number_of_test_cases = COUNT(test_case_run_inputs for this run)`, replacing any stale creation-time count. The creation-time value computed in `createRun()` is a preview value only and may differ from the post-snapshot count if test cases were added / disabled / invalidated between suite creation and snapshot time.

**PENDING → RUNNING ordering:** The snapshot tx commits first, then `updateToRunning()` is invoked **immediately** in the next statement (no intervening async work, no yield, no HTTP I/O). The two writes are not in the same tx — the snapshot uses `ISOLATION_REPEATABLE_READ` while `updateToRunning` uses the default isolation — but the executor code path runs both writes sequentially within the same async task with no scheduling point between them. Consequently, an external observer can theoretically catch a PENDING run with a non-null `suite_snapshot`, but this window is sub-millisecond and not observable in practice via the API.

**REPEATABLE READ justification:** REPEATABLE READ is chosen over READ COMMITTED because the snapshot phase executes multiple SELECTs across `test_suites`, `test_cases`, and related tables. Under READ COMMITTED each SELECT could see a different view if a user concurrently edits the suite. REPEATABLE READ gives all reads a single point-in-time view. A single complex query (joining all needed tables) could in principle work under READ COMMITTED, but keeping reads small and isolation explicit is simpler.

**Serialization-failure handling:** On PostgreSQL serialization failure (SQLState `40001`), the snapshot phase SHALL be retried up to 2 times with a short backoff. If all retries fail, the run SHALL be marked FAILED with category `INTERNAL` and code `SNAPSHOT_SERIALIZATION_CONFLICT`.

Each retry attempt runs in a fresh `TransactionTemplate.execute()` block. PostgreSQL rollback on SQLSTATE 40001 removes partial INSERT rows; additionally, each attempt SHALL issue `DELETE FROM test_case_run_inputs WHERE run_id = :runId` as its first statement to defensively clear any leaked rows from a prior attempt.

### D5: Executor reads from snapshot — single code path, synthesized fallback for legacy runs

**New flow:**
```
TestSuiteEvaluationJob.executeRunAsync()
  → snapshot phase (D4)                           ← new
  → buildContext(run) from run.suiteSnapshot       ← was: buildContext(run, liveSuite)

InProcessEvaluationExecutor.execute()
  → page through test_case_run_inputs(runId)       ← was: OFFSET/LIMIT on test_cases
  → pass (inputRow, context) to worker

EvaluationWorker.execute()
  → use context.getRequestTemplate()/getInputBindings()  ← was: resolvedRequestService.resolveRequest()
  → read deploymentRef/endpointRef from context          ← was: from TestSuite object
```

**Legacy runs (null snapshot):** `buildContext()` synthesizes a transient in-memory snapshot via `SuiteSnapshotBuilder.build(liveSuite)` and reads live TCs via `findEnabledValidByTestSuiteId`. The `InProcessEvaluationExecutor` receives a fully populated `EvaluationContext` regardless of snapshot source — no branching inside the executor.

**Inconsistent snapshot state** (exactly one of `suite_snapshot` / `test_case_run_inputs` is missing): fail the run fast with `SNAPSHOT_STATE_INCONSISTENT` error.

### D6: `ResolvedRequestService` — new overload without DB read

The existing `resolveRequest(UUID suiteId, UUID testCaseId)` will remain for the `TryItOut` / preview path (non-execution). The executor path calls a new overload `resolve(RequestTemplateDto, List<InputBindingDto>, Map<String,Object> data)` — no DB read — passing pre-resolved config from context and pre-read data from the inputs row.

## Risks / Trade-offs

**[Storage growth]** → Each run adds bounded `suite_snapshot` (few KB) plus ~N KB per test case in `test_case_run_inputs`. For 50K RAG-sized TCs, could be GBs. → Mitigation: CASCADE DELETE handles explicit run deletion; `TestCaseRunInputsRetentionJob` periodically purges inputs for completed/failed runs older than `evaluation.run.inputs-retention-days` (default 1 day). `test_case_data` is durably preserved in analytics; V2 rerun uses replay from `request_body`, so inputs are safe to purge.

**[Snapshot phase latency before execution]** → 10–15s of pure DB I/O before the first HTTP call is made. Run remains PENDING during this window. → Mitigation: Acceptable trade-off vs. data integrity. If needed, expose a `SNAPSHOTTING` intermediate status in a future change.

**[Schema evolution]** → New suite fields must be added to `SuiteSnapshotDto`. → Mitigation: `@JsonIgnoreProperties(ignoreUnknown = true)` + `snapshotVersion` strategy in D2. Document in AGENTS.md.

**[Fallback complexity during rolling deploy]** → In-flight runs created before migration have no snapshot. The synthesized-snapshot path handles this. → Mitigation: Remove fallback in a future cleanup release.
