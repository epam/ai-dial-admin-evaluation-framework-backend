## Context

Multi-turn is emergent from the data: a multi-turn is an ordered group of `test_cases` rows sharing a client-supplied `multi_turn_id` (`VARCHAR(36)`). At snapshot time these rows are assembled into one `test_case_run_inputs` row, then executed by `MultiTurnExecutor`, which emits **one `TestCaseRunResult` per surviving turn** — each turn now carrying its own `test_case_id`. Phase-2 derives one `EvalSummary` per result row.

The frontend groups turn rows back into a multi-turn for display. It has an asymmetric toolkit today:

- `test_case_run_results` carries a shared `trace_id` per multi-turn span (+ `run_index`), so results are groupable.
- `test_case_eval_summaries` was created (V1.5) **without** `trace_id` and never gained one; it carries only `turn_index`/`total_turns` (V1.14). Since each turn is its own `test_case_id`, summary turns have **no** grouping key at all.

The natural grouping key already exists upstream — `multi_turn_id` — but is dropped at the analytics boundary. This change propagates it onto both analytics tables and exposes it on both read surfaces.

Constraints: JDBC-only + jOOQ typed DSL; analytics tables are append-only with keyset pagination on `(created_at_ms, id)`; UUIDs stored as `VARCHAR(36)`; the eval-summary batch-write endpoint is a public contract that must stay byte-compatible for existing single-turn callers.

## Goals / Non-Goals

**Goals:**
- One explicit, stable grouping key (`multiTurnId`) on both the results and summaries read surfaces (API + CSV).
- Preserve the existing `test_cases` convention: nullable `UUID`, `VARCHAR(36)` storage, NULL ⇒ single-turn, DTO omits when null.
- Keep the public `POST /api/v1/analytics/eval-summaries` contract backward-compatible.
- Keep the schema time-partition-ready, consistent with the deliberate `created_at_ms`-in-keys design already present in V1.1.
- Record forward-looking ClickHouse access-pattern intent so the future CH provider is coherent.

**Non-Goals:**
- No server-side group-by/filter-by-multi-turn query endpoint. FE groups client-side within an already-fetched page; the new index is opportunistic, not backing a new query.
- No change to `trace_id`, the keyset spine, snapshot version (`"2"`), or any UNIQUE/natural key.
- No implementation of the ClickHouse provider — only a recorded design note.
- No re-derivation of `multi_turn_id` for already-persisted historical rows beyond the `NULL` default backfill (single-turn semantics).

## Decisions

### D1 — Persist a real column on both tables (not derive from `trace_id`)
`trace_id` is a span id, not the multi-turn identity, and summaries lack it entirely. There is no derivation path, so `multi_turn_id` must be a persisted column on both `test_case_run_results` and `test_case_eval_summaries`. Populating the summary requires the value on the result first (summaries are derived from result rows), so both tables get it regardless of which surface "needs" it.
*Alternative rejected:* expose `trace_id` on the summary DTO instead. It groups by span, not multi-turn, is opaque to the client, and diverges from the upstream `multi_turn_id` the FE already knows.

### D2 — Type & nullability: nullable `UUID` / `VARCHAR(36)`, NULL for single-turn, omit-when-null
Mirrors `TestCase`/`TestCaseRunInput`/`TestCaseResponseDto` exactly (`private UUID multiTurnId;`, DB `VARCHAR(36)`). Single-turn rows store `NULL`; response DTOs use `@JsonInclude(NON_NULL)` so single-turn payloads are unchanged. Column is a `NOT NULL`-free plain nullable column; backfill of existing rows is implicitly `NULL` (correct single-turn semantics), a metadata-only `ADD COLUMN` (no table rewrite on PG 11+).

### D3 — Grouping index shape: `(test_suite_run_id, multi_turn_id, created_at_ms)`, non-unique, both tables
Equality/grouping columns lead; `created_at_ms` trails. Rationale:
- A **non-unique** index has no partition-key-inclusion requirement, so partitioning does not *force* `created_at_ms` here — partition pruning comes from a future `PARTITION BY RANGE(created_at_ms)` on the table plus a time predicate in the query, not from index contents.
- `created_at_ms` is included anyway because (a) it matches the schema's established partition-ready philosophy (V1.1 already bakes `created_at_ms` into the PK and the UNIQUE natural key — the latter *is* required for a partitioned UNIQUE constraint), (b) as a **trailing** member it aligns with the `(created_at_ms, id)` keyset spine and enables ordered per-group scans, and (c) it is harmless.
- Leading `created_at_ms` would defeat the equality-grouping the index exists for, so it goes last.
*Alternatives rejected:* `(test_suite_run_id, multi_turn_id)` only (loses spine alignment / ordered per-group reads); no index (FE grouping works without it, but the project convention is to make analytics tables partition-ready and index-consistent). Never add `multi_turn_id` to a UNIQUE key — `(test_case_id, run_index, turn_index, created_at_ms)` already uniquely identifies a row and `multi_turn_id` is redundant, nullable grouping metadata.

### D4 — Public batch-write DTO: optional, nullable, mapper-defaulted
`EvalSummaryBatchWriteItemDto.multiTurnId` is a nullable `UUID` with **no** `@NotNull`; `EvalSummaryMapper` maps `null → null`. Existing single-turn external callers that omit it stay byte-compatible — identical to how `turnIndex`/`totalTurns` were introduced. The internal producer (multi-turn path) always supplies it from the source result row.

### D5 — CSV identity-column placement: before `turnIndex`
Insert `multiTurnId` at `…runIndex, multiTurnId, turnIndex, totalTurns` in `EvalSummaryExportColumnPlanner`, mirroring the test-case CSV order (`testCaseName, multiTurnId, turnIndex`). This is an additive, appended-family column; export column-count limits are unaffected in practice (one new identity column).

### D6 — Population points in the execution path
`MultiTurnExecutor` sets `multiTurnId = input.getMultiTurnId()` on every per-turn `TestCaseRunResult` (SUCCESS and the fail-fast ERROR row) and on the degenerate "no readable turns" ERROR row. `EvaluationWorker` sets it on the broken `0/0` sentinel row (a broken multi-turn still *is* a multi-turn). The single-turn execution path leaves it `NULL`. Summaries inherit it via the result → batch-write-item → `EvalSummary` mapping.

### D7 — ClickHouse design intent (recorded, not implemented)
Per the approved CH plan (`ORDER BY` = filter cols prepended to natural key; monthly `PARTITION BY`; CH serves reads only via the query DSL; cursor-list + CSV stay Postgres-only): the CH `test_case_run_results`/`test_case_eval_summaries` equivalents will `PARTITION BY toYYYYMM(created_at_ms)` and **prepend `multi_turn_id` to the `ORDER BY` natural key** if multi-turn grouping/filtering becomes a first-class DSL read pattern. The Postgres btree index added by D3 is a Postgres-only optimization that the CH provider will not carry over. This note lives here (and in the CH provider's future OpenSpec change), not in code.

## Risks / Trade-offs

- **[Historical rows have `NULL` multiTurnId even if they were multi-turns]** → Acceptable: existing multi-turn results remain groupable by their existing `trace_id`; only newly-written rows carry `multi_turn_id`. No backfill from source `test_cases` is attempted (source rows may have changed since the run; the run's snapshot is the authority and does not persist per-result `multi_turn_id` historically). Documented as the migration's single-turn `NULL` default behavior.
- **[Two migrations + `generateJooq` drift]** → `JooqSchemaDriftTest` guards this; run `./gradlew generateJooq` and commit generated sources in the same change. Migrations are idempotent (`ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) so local re-runs are no-ops.
- **[Index write cost on append-only hot path]** → One extra btree per table on inserts. Low: analytics writes are batched bulk inserts, and the index is narrow (two `VARCHAR(36)` + one `BIGINT`). No unique constraint, so no conflict-check overhead.
- **[Public DTO field could be misread as required]** → Mitigated by `@Schema` documentation marking it optional/nullable and the absence of `@NotNull`; covered by a functional test asserting a single-turn payload without `multiTurnId` still succeeds.

## Migration Plan

1. Add `V1.16__AddMultiTurnIdToTestCaseRunResults.sql` and `V1.17__AddMultiTurnIdToEvalSummaries.sql` (idempotent `ADD COLUMN IF NOT EXISTS multi_turn_id VARCHAR(36)` + `CREATE INDEX IF NOT EXISTS … (test_suite_run_id, multi_turn_id, created_at_ms)`).
2. `./gradlew generateJooq`; commit generated sources.
3. Model, record-mapper, repository, executor, DTO, mapper, CSV planner changes.
4. Update `docs/database-schema.md`, AGENTS.md multi-turn paragraph, `docs/patterns/suite-run-snapshot.md` if it enumerates result/summary columns.
5. **Rollback:** additive-only; a rollback drops the two columns/indexes with no data-shape dependency (no reads assume the column is non-null). Deployed code tolerates the column being absent only if reverted together — standard forward-migration discipline applies.

## Open Questions

None — all design decisions resolved during grilling.
