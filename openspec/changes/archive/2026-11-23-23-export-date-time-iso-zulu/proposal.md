## Why

GH #23: evaluation-results CSV timestamps are unreadable epoch numbers, often displayed in scientific notation. Human-readable UTC timestamps should also be accepted by results import without changing stored values.

## What Changes

- **BREAKING**: CSV `createdAt` and `computedAt` become ISO 8601 UTC strings with three fractional digits (`2026-05-25T10:22:12.226Z`).
- Import `startedAt` and `completedAt` accepts UTC ISO timestamps at millisecond precision alongside legacy epoch-millisecond integers.
- Preserve epoch-millisecond DB/models and JSON APIs (including export preview); leave durations, arbitrary data, and existing CSV columns unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `eval-summary-export`: CSV-only timestamp formatting.
- `eval-results-import`: UTC ISO timestamp parsing and legacy compatibility.

## Impact

Planned: analytics CSV service/parser and a shared injectable timestamp codec; no DB, dependency, configuration, security, or new-package changes. Existing pagination/batch limits remain. Update endpoint documentation and the timestamp convention to clarify the CSV exception. Unit tests cover edge cases; exactly two new functional happy paths verify export and import. Roll out directly; CSV consumers must adapt, while existing imports remain supported. Export and import retain their distinct column contracts, not a new round-trip format.
