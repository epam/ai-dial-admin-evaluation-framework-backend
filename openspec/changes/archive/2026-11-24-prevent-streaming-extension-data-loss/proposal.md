## Why

Streaming assembly currently overwrites repeated stage text and nested stage attachment arrays, replaces `custom_content.annotations`, and leaves `custom_fields.annotations` fragmented. Field-aware merging prevents these losses without filtering unknown extension data.

## What Changes

- Concatenate stage `name`/`content` and indexed stage attachment `title`/`data`; retain existing stage ordering and unindexed-entry behavior.
- Merge top-level annotations independently in both extensions by numeric index or HTML-tag selector identity; concatenate `body.title`/`body.quote`, preserve unknown properties and unkeyed entries, and do not normalize wire shapes.
- Replace non-null `custom_content.state`/`form_schema` snapshot objects rather than retaining stale properties.
- Preserve generic merge rules outside these named exceptions, null-as-no-update, explicit custom-fields array replacement mode, and defensive snapshots.
- Planned work only. Non-goals: raw-event persistence, top-level attachment policy changes, generic string concatenation, reset semantics, other SSE modes, API/DB/config changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `eval-execution-engine`: Field-aware OpenAI streaming extension accumulation and compatibility boundaries.

## Impact

Runner `.job` accumulators and synthetic tests; try-it-out shares assembly and requires parity coverage. A small package-private annotation helper may be introduced; no new package, Spring bean, dependency, schema, configuration, or API envelope. Assembled values change intentionally; existing stored results are not rewritten. Rollout is the normal deployment with no migration. Risks: confusing fragments with snapshots or coalescing unrelated annotations; explicit field/identity policies and boundary tests constrain both. Tests use fabricated data only; sensitive local payloads must never enter fixtures or artifacts.
