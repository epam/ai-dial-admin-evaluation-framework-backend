## Why

The CSV export today carries a special-case branch for **legacy runs** (those whose `test_suite_runs.suite_snapshot` is `NULL`, predating the snapshot phase): when invoked, the service rehydrates a transient `SuiteSnapshotDto` by reading the **live** `TestSuite` via `TestSuiteRepository.findById` and calling `SuiteSnapshotBuilder.build(liveSuite)`. Only when the live suite is also gone does the export return `HTTP 422` (`SNAPSHOT_SUITE_MISSING`).

That fallback contradicts the core contract of the export — "the column manifest is derived **exclusively** from the run's frozen `suite_snapshot`" — and is a known weak spot:

- The synthesized snapshot reflects the **current** suite schema, not the schema at run time. Two exports of the same legacy run, before and after a suite edit, would produce different columns. That's exactly the divergence the snapshot phase was introduced to prevent.
- It pulls the export path into the meta domain (`TestSuiteRepository`, `SuiteSnapshotBuilder`) and adds a second meta read inside the snapshot-resolution step.
- The "live suite missing" sub-branch is the only realistic way to hit `SNAPSHOT_SUITE_MISSING` today, which makes the error code feel inconsistent.

Legacy runs are a transient population: they predate the snapshot-phase rollout and will not be regenerated. Snapshot retention is unlimited and every run created since the snapshot phase landed carries a non-null `suite_snapshot`. The cost of supporting them in the export is permanent; the user value is bounded and shrinking.

## What Changes

- **BREAKING (for legacy runs)**: `POST /api/v1/analytics/eval-summaries/export.csv` and `GET /api/v1/analytics/eval-summaries/export/preview` SHALL return `HTTP 422` with `SNAPSHOT_SUITE_MISSING` whenever `test_suite_runs.suite_snapshot` is `NULL` (or blank), regardless of whether the live `TestSuite` still exists. The live-suite fallback via `SuiteSnapshotBuilder.build(liveSuite)` is removed from the export path.
- Drop `TestSuiteRepository` and `SuiteSnapshotBuilder` as constructor dependencies of `EvalSummaryExportService`. The service's snapshot-resolution step becomes a single `objectMapper.readValue(suiteSnapshotJson, SuiteSnapshotDto.class)` + version check; null/blank → throw `SnapshotSuiteMissingException` directly.
- Update functional tests in `EvalSummaryExportFunctionalTests` that exercised the synthesize-from-live-suite path so they instead assert the 422 response. Add a test for the "null snapshot, live suite present" case to lock in the new behavior.
- Update the OpenAPI 422 description for both endpoints to drop the "missing live suite" qualifier — the trigger is now "snapshot missing", period.
- Update `openspec/specs/eval-summary-export/spec.md` requirements that reference legacy-run handling, including the preview-endpoint scenarios that share the legacy-run rule.
- No DB migration. No configuration property changes. `SuiteSnapshotBuilder` and `SnapshotSuiteMissingException` themselves stay (still used by `TestSuiteEvaluationJob` for the snapshot phase).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `eval-summary-export`: the **"Legacy run snapshot handling"** requirement and its scenarios change — the synthesize-from-live-suite path is removed; a null/blank `suite_snapshot` always produces `HTTP 422` (`SNAPSHOT_SUITE_MISSING`). The preview endpoint's "shares legacy-run handling" scenario is rewritten to assert the same. The "Column set is derived from run snapshot" requirement gains a tightened invariant: the service SHALL NOT read the live `TestSuite` for any reason on the export or preview path.

## Impact

- **Code**:
  - `service.domain.analytics.EvalSummaryExportService` — `resolveSnapshot` simplified; constructor and fields lose `TestSuiteRepository`, `SuiteSnapshotBuilder`, and the `TestSuite` import. The `MetaSetup` record is unaffected (it already carries only `run` and `snapshot`).
- **APIs**: No route shape changes. Behavior change is observable only on legacy runs that previously succeeded: those now return `HTTP 422` with `SNAPSHOT_SUITE_MISSING` and the existing `ErrorView` body. The OpenAPI 422 description text is refreshed.
- **Database**: No schema migrations. No data migration. No retention/backfill operation.
- **Configuration**: No new properties.
- **Security**: No change.
- **Dependencies**: No change.
- **Tests**:
  - `EvalSummaryExportFunctionalTests` — adjust or remove tests that asserted successful export for `suite_snapshot IS NULL`. Add one test per endpoint asserting 422 + `SNAPSHOT_SUITE_MISSING` for the null-snapshot case.
- **Docs**: `openspec/specs/eval-summary-export/spec.md` updated via the delta spec; `openspec/specs/README.md` one-liner unchanged. No update to `AGENTS.md`, `docs/database-schema.md`, or `docs/configuration.md` (no project-wide convention change, no schema change, no config change).
- **Out of scope**: any one-shot data migration to backfill `suite_snapshot` for legacy runs — the change is intentionally a behavioral simplification, not a data-rescue effort.
