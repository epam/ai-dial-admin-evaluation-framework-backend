## Context

Users need to duplicate test suites (including test cases, TSMDs, and DIAL files) to create variations. Currently there is no clone/copy capability — users must create suites from scratch and re-import everything.

The codebase already has:
- `RevalidationService` for async test case + TSMD revalidation (batched, with progress tracking)
- `DialFileClient` with `list()`, `download()`, `upload()` methods
- `FileService.deleteAllBySuiteId()` as a pattern for iterating suite files
- `TestSuiteMapper` (manual, not MapStruct) for entity↔DTO mapping
- Paginated repository reads (`findBatchByTestSuiteId`)

Missing:
- Batch INSERT for `TestCaseRepository` and `TestSuiteMetricDefinitionRepository` (only single `save()` exists today)

## Goals / Non-Goals

**Goals:**
- Deep copy of a test suite: suite config, test cases, TSMDs, and DIAL files
- Patch-style overrides for suite-level fields on the cloned copy
- Paginated copying of child entities to handle large suites
- Graceful handling of missing/inaccessible files during copy
- Async revalidation of the cloned suite after creation

**Non-Goals:**
- Suite type change during clone (DEPLOYMENT ↔ MCP_TOOL) — deferred to v2
- Selective cloning (e.g., clone only some test cases) — clone is always a full copy
- Cloning test suite runs or analytics data

## Decisions

### D1: Pre-generate UUID for new suite before file copy

**Decision:** Generate `newSuiteId = UUID.randomUUID()` upfront, before any file operations or DB writes.

**Rationale:** File references use the pattern `@ef/suites/{suiteId}/filename`. We need the new suite's UUID to construct target file paths and rewrite references in JSONB data. The existing codebase always generates UUIDs in application code (not DB sequences), so pre-assigning is consistent.

**Alternatives considered:**
- Insert suite first, then copy files: Would require the DB transaction to span file I/O, making it long-running and fragile.

### D2: File copy before DB transaction, best-effort cleanup on failure

**Decision:** Copy DIAL files pre-transaction. If the subsequent DB transaction fails, perform best-effort cleanup of copied files (same pattern as `TestSuiteService.delete()` which does DB-first, then best-effort file cleanup).

**Rationale:** Files should exist before we reference them. A brief window where orphaned files exist (if DB fails) is acceptable — it mirrors the existing delete flow's tolerance for orphaned references during failures.

See D7 for the complete canonical clone flow, including synchronous suite validation (step 4a).

### D3: String replacement for file reference rewriting

**Decision:** Use `String.replace("@ef/suites/{sourceId}/", "@ef/suites/{newId}/")` on raw JSONB strings.

**Rationale:** The `@ef/suites/{uuid}/` pattern is unique enough that string replacement won't produce false positives. This is simpler and faster than parsing JSON trees, and covers all nested locations uniformly (test case data, input bindings, request template parts).

**Applied to:**
- Suite-level: `inputBindings`, `requestTemplate`, `argumentTemplate` (raw JSON strings in entity)
- Test case-level: `data`, `requestTemplateOverride`, `inputBindingsOverride` (raw JSON strings)
- TSMD-level: `configBindings`, `inputBindings` (raw JSON strings; null-safe: skip if field is null)

### D4: Add batch INSERT methods to repositories

**Decision:** Add `batchInsert(List<TestCase>)` to `TestCaseRepository` and `batchInsert(List<TestSuiteMetricDefinition>)` to `TestSuiteMetricDefinitionRepository`.

**Rationale:** Current repositories only have single `save()`. Inserting thousands of test cases one-by-one would be slow. The existing `TestCaseRepository.batchUpdate()` demonstrates the JDBC batch pattern — batch INSERT follows the same approach.

**Alternative considered:**
- Reuse single `save()` in a loop: Works but is O(N) round-trips vs O(1) for JDBC batching. Unacceptable for suites with thousands of test cases.

### D5: Dedicated TestSuiteCloneService

**Decision:** Create `TestSuiteCloneService` in `service.domain` package to orchestrate the clone flow. It will inject `TestSuiteRepository`, `TestCaseRepository`, `TestSuiteMetricDefinitionRepository`, `FileService`, `SuiteValidationService`, `TestSuiteRequestValidator` (new component — see below), `RevalidationService`, `TestSuiteMapper`, `AuthorResolver`, `RevalidationProperties`, `PlatformTransactionManager` (`@Qualifier("metaTransactionManager")`) — construct `new TransactionTemplate(transactionManager)` in the constructor (same pattern as `TestSuiteService.delete()`; no `TransactionTemplate` bean is registered in the context), `Clock` (from `ClockConfiguration` bean), and `EndpointSchemaRefResolver` (needed for `endpointRef` resolution in the pre-validation normalization step). `DialFileRefResolver` is NOT injected — string replacement (D3) works on raw strings using the literal `"@ef/suites/{id}/"` pattern without needing alias resolution; `FileService` already has `DialFileRefResolver` internally for its own operations.

**Extraction of hard validators into `TestSuiteRequestValidator`:** `TestSuiteService` contains three private methods — `validateSuiteTypeFields`, `validateTestSuiteSchemas`, and `validateTemplateLimits` — that perform hard (HTTP 400) validation. Because these are `private`, `TestSuiteCloneService` (a separate class) cannot call them directly. To enable reuse without coupling, these methods are extracted into a new `@Component` named `TestSuiteRequestValidator` in `service.domain` with public visibility. `TestSuiteService` is updated to delegate to `TestSuiteRequestValidator` (replacing the private method calls), and `TestSuiteCloneService` injects `TestSuiteRequestValidator` to invoke the same validation chain.

**Rationale:** The clone operation is complex enough (file copy + transaction + revalidation) to warrant its own service rather than adding to the already-large `TestSuiteService`. The transactional DB write block covers only the DB writes (steps 5a-5c above), not the file copy.

**Important:** The transactional DB write step MUST NOT be a `@Transactional` method on the same class as the orchestrating `clone()` method — Spring AOP self-invocation (`this.method()`) bypasses the proxy and the transaction never starts. Use `TransactionTemplate` (constructed locally from the injected `PlatformTransactionManager`, same pattern as `TestSuiteService.delete()`) for the transactional block instead.

**Timestamp handling:** `TransactionTimestampAspect` is AOP-based and fires only on `@Transactional`-annotated methods. When `TransactionTemplate.execute()` is used, the aspect never fires, so calling `TransactionTimestampContext.getTimestamp()` inside `createWithId()` or `batchInsert()` would throw `IllegalStateException("Timestamp not initialized")`. To avoid this, the clone service MUST capture `clock.millis()` before the `TransactionTemplate.execute()` call and pass it as an explicit `long cloneTimestamp` parameter to all insert methods. The new `createWithId(TestSuite entity, long timestamp)`, `batchInsert(List<TestCase> testCases, long timestamp)`, and `batchInsert(List<TestSuiteMetricDefinition> tsmds, long timestamp)` methods SHALL accept an explicit timestamp parameter and use it directly for `created_at_ms` and `updated_at_ms`, rather than calling `TransactionTimestampContext.getTimestamp()`.

**Component interaction:**
```
TestSuiteController
    │
    ▼
TestSuiteCloneService             ← new, orchestrates full flow
    ├─ TestSuiteRepository        ← fetch source, save clone
    ├─ TestCaseRepository         ← paginated read + batch insert
    ├─ TsmdRepository             ← paginated read + batch insert
    ├─ FileService                ← file copy (new method)
    ├─ SuiteValidationService     ← synchronous soft suite-level validation
    ├─ TestSuiteRequestValidator  ← new, hard validators extracted from TestSuiteService
    ├─ EndpointSchemaRefResolver  ← endpointRef resolution before validation
    ├─ RevalidationService        ← trigger async revalidation
    ├─ TestSuiteMapper            ← entity building
    └─ AuthorResolver             ← resolve createdBy from JWT
```

### D6: Dedicated TestSuiteCloneRequestDto with null-means-inherit semantics

**Decision:** New DTO with only `name` as `@NotBlank`. All other fields are optional — `null` means "inherit from source." No mechanism to explicitly clear a field to null.

**Rationale:** Reusing `TestSuiteRequestDto` doesn't work because it has `@NotBlank` on `deploymentRef` and other required-for-create fields. A dedicated DTO makes the patch semantics explicit and avoids validation confusion.

### D7: Reuse RevalidationService and batch size config

**Decision:** After the clone transaction commits, call `revalidationService.startRevalidation(newSuiteId)`. This reuses the existing async revalidation infrastructure (batched processing, progress tracking, timeout handling). The clone's paginated copying uses the same batch size as revalidation.

**Rationale:** The revalidation mechanism already handles batched test case processing with progress reporting. No need to build separate validation infrastructure for clone.

**Validation split — suite vs. test cases/TSMDs:**
- **Suite entity**: Suite-level validation (via `SuiteValidationService.validateSuite()`) SHALL be performed **synchronously** during the clone flow, same as `TestSuiteService.create()` does. The call MUST happen after the new suite entity is built in memory (step 3/overrides applied) but before the DB transaction (step 6). `SuiteValidationService.validateSuite(dto, null)` takes a `TestSuiteRequestDto` and a nullable UUID — pass `null` (same as create). The clone service constructs the appropriate DTO from the new entity's fields (same pattern as create) and applies the validation result (`isValid`, `validationWarnings`) to the entity before inserting it. The clone mapper MUST NOT force `isValid=false` or `validationWarnings=[]` on the suite entity — those values are set by the synchronous validation step.
- **Test cases and TSMDs**: These start with `isValid = false` (no synchronous validation) and are validated asynchronously by `RevalidationService`. `RevalidationService.runRevalidationAsync()` revalidates test cases and TSMDs only — it never calls `SuiteValidationService.validateSuite()` — so the suite entity itself is NOT re-validated by the async revalidation step.

**Updated clone flow steps:**
```
1. Fetch source suite (404 if missing)
2. Generate newSuiteId
3. Apply overrides + rewrite file refs in suite-level JSONB fields IN MEMORY → build new suite entity
   └─ File ref rewriting performed by TestSuiteMapper during entity construction (no DB write yet)
4. Copy files: list source folder → download each → upload to new folder
   └─ Skip missing files (log warn, continue)
4a. Run synchronous suite-level validation on the already-built entity (refs already rewritten):
    - The entity is fully built with all inherited + overridden fields before validation runs.
      Null override fields in the request DTO mean "inherit from source", so the entity already has
      the source's deploymentRef/mcpDeploymentRef/toolRef/etc. populated — validateSuiteTypeFields()
      is safe to call on the full entity-derived DTO.
    - Call `testSuiteMapper.toRequestDto(newSuiteEntity)` to obtain the DTO from the fully-built entity.
    - Invoke the FULL validation chain from TestSuiteService.create(), in order:
        (i)   validateSuiteTypeFields(dto)        — hard-fails (HTTP 400) on invalid type-specific fields
        (ii)  validateTestSuiteSchemas(dto, null)  — hard-fails on invalid JSON schemas
        (iii) validateTemplateLimits(dto)           — hard-fails on template limit violations
        (iv)  validateSuite(dto, null)             — soft validation; result applied to entity
    - Apply the validateSuite result by setting entity.setValid(result.isValid()) and
      entity.setValidationWarnings(result.validationWarnings()); hard validators (i)–(iii) throw
      on failure and the exception propagates normally (finally block handles cleanup)
5. BEGIN TRANSACTION  ← first DB write is here
   a. INSERT new suite via createWithId() (entity already has rewritten refs + validation result)
   b. Paginated loop: read source test cases → rewrite file refs IN MEMORY → batch INSERT
   c. Paginated loop: read source TSMDs → rewrite file refs in `configBindings` and `inputBindings` in memory (null-safe: skip if field is null) → assign new UUIDs → batch INSERT
6. COMMIT
7. Trigger async revalidation (covers test cases and TSMDs)
8. Return 201 + TestSuiteUpdateResultDto
FINALLY → best-effort delete of copied files on ANY failure after step 4 — including validation
          failures thrown in step 4a (hard validators), DataIntegrityViolationException rethrown as
          409, and all other runtime exceptions — because `cloneSucceeded` remains `false` until
          step 8 returns the successful result
```

Note: The `finally` block uses a `boolean cloneSucceeded = false` flag (set to `true` before returning the successful result) — cleanup only runs when `!cloneSucceeded`. If the 404 exception is thrown at step 1 before files are copied, the cleanup call to `deleteAllBySuiteId(newId)` is a harmless no-op (DIAL returns empty for a non-existent folder).

### D8: Reuse TestSuiteUpdateResultDto for response

**Decision:** Return `TestSuiteUpdateResultDto` which already has `suite` (TestSuiteResponseDto) + `revalidationTask` (RevalidationTaskDto).

**Rationale:** The clone response shape is identical to a suite update that triggers revalidation. No need for a new response DTO.

### D9: Add copyFilesBetweenSuites to FileService

**Decision:** Add a `copyFilesBetweenSuites(UUID sourceId, UUID targetId)` method to `FileService` that lists files in the source folder, downloads each, and uploads to the target folder. Missing files are logged and skipped.

**Rationale:** File copy logic belongs in `FileService` (owns all file operations), not in the clone service. The method follows the same iteration pattern as `deleteAllBySuiteId`.

Returns a list of successfully copied filenames so the clone service knows what was actually copied.

## Risks / Trade-offs

**[Large suite file copy is slow]** → Files are copied synchronously before the DB transaction. For suites with many large files, this could take significant time. Mitigation: The endpoint response time scales with file count/size. This is acceptable for v1 — async file copy could be added later if needed.

**[Orphaned files on TX failure]** → If the DB transaction fails after files are copied, orphaned files remain in DIAL storage. Mitigation: Best-effort cleanup in a `finally` block using a `boolean cloneSucceeded = false` flag (set to `true` before returning the successful result) — cleanup runs only when `!cloneSucceeded`. Orphaned files in DIAL storage are low-impact (no DB references point to them).

**[No partial clone recovery]** → If the endpoint fails mid-way (e.g., after copying some test cases), the entire clone fails atomically (transaction rollback). The user must retry. This is the correct behavior — partial clones would be confusing.

**[Name uniqueness race condition]** → Two concurrent clone requests with the same name could both pass validation but one fails on DB unique constraint. Mitigation: Catch `DataIntegrityViolationException` and return `UNIQUE_CONSTRAINT_VIOLATION` error, same as suite create.

## Open Questions

None — all design decisions are resolved based on the exploration discussion.
