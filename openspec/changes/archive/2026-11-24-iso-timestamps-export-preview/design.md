## Context

See proposal.md for motivation. The existing CSV writer applies `CsvTimestampCodec` only for `createdAt` and `computedAt`; `previewAsJson` bypasses conversion and emits the planner's raw values. The codec's null formatting is an empty string, appropriate for CSV but not JSON preview. A short design records that compatibility/null distinction.

## Goals / Non-Goals

**Goals:** Reuse the existing timestamp codec at the preview presentation boundary, with no change to data extraction or fetching.

**Non-Goals:** New beans/packages, global serialization changes, planner changes, schema/config changes, or changing other timestamp APIs.

## Decisions

- Keep the planner and `EvalSummaryExportRow` raw. In `EvalSummaryExportService.previewAsJson`, use the existing timestamp-column allowlist and injected codec for non-null timestamp cells; pass null and all other values through unchanged. This preserves structured JSON cells without a new helper/component.
- Alternatives: changing planner extractors would spread presentation rules into shared extraction; global Jackson conversion would affect ordinary APIs; frontend-only formatting would leave the preview API inconsistent with the requested contract. The localized backend conversion is the smallest change.
- Component flow remains controller → export service → frozen-context resolution and paged repository read → row extraction → timestamp-only formatting → typed JSON arrays. Existing meta/analytics transaction templates and ten-row limit remain unchanged. Formatting occurs outside DB transactions.
- No new catches or fallback behavior: use the existing codec unchanged. No model or persistence representation changes.
- Update both export and preview OpenAPI descriptions, the registered preview response example, and existing timestamp regression tests. Update the delta's implementation status/notes when verified; reconcile stale main-spec implementation notes during delta sync/archive. No project-wide convention changes: config.yaml and spec index need no change; AGENTS.md should clarify the narrowly scoped preview exception to its API timestamp convention.

## Risks / Trade-offs

- [Preview consumers currently expect numbers] → Explicitly document the number-to-string change; coordinate any dependent frontend adjustment.
- [CSV null becomes an empty string if reused blindly] → Guard null before calling the codec; test JSON null directly.
- [Accidental conversion of numeric durations or payload values] → Match only the two exact column names and assert unchanged typed cells.

## Migration Plan

No data migration. Deploy the backend change with any required consumer adjustment. Rollback restores numeric preview timestamps without changing stored values or CSV output. Run targeted service tests and the nested export functional suite before checking tasks complete, then Spotless and Checkstyle.
