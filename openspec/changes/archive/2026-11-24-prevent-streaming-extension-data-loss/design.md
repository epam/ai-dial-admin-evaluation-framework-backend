## Context

See proposal.md for motivation and `specs/eval-execution-engine/spec.md` for the contract. `CustomContentAccumulator` uses an indexed map for stages/attachments and generic object merging elsewhere; `CustomFieldsAccumulator` wraps `JsonObjectDeltaAccumulator` with selectable array mode. `StreamingResponseAccumulator` is shared by evaluation and try-it-out. Two synthetic stage regressions already fail; sensitive `tmp` payloads are investigation-only.

## Goals / Non-Goals

Implement explicit exceptions without a general policy registry or new Spring wiring. Leave generic JSON merge behavior, top-level attachment ordering, other SSE modes, unsuccessful-parse behavior, and explicit array replacement intact. No API envelope, DB model, transaction, configuration, or raw-event persistence changes.

## Decisions

- **Scoped policies over global concatenation or full Chat parity.** Concatenate only stage name/content, stage attachment title/data, and annotation body title/quote. Metadata remains replaceable. Unknown extension fields remain available rather than adopting Chat's whitelist.
- **Small helpers within runner `.job`.** Extend existing accumulation seams; a package-private annotation accumulator shared by the two extensions avoids duplicated identity logic. These are per-response stateful utilities, not injectable converters or Spring beans. JSON construction stays typed Jackson nodes; reuse defensive-copy behavior.
- **Stage attachments are stage-local.** Reuse indexed-array ordering rules within each stage; do not change top-level attachment behavior. Only append when incoming and existing text are strings; null/empty text always contributes no update (even after a non-string), and non-empty incompatible types replace without coercion.
- **Annotation identity is deliberately narrow.** Valid non-negative integral index wins; otherwise fall back to object-shaped target/selector with exact `target.selector.type="html_tag"` and non-empty string `target.selector.id`, including when index is invalid. Distinct identity kinds never collide. Arbitrary `id` fields and CSS selectors remain unkeyed. Preserve null/scalar/unrecognized entries and first-seen order. Use recursive merging with atomic append of ordinary nested arrays, preserving copied null properties, and scoped text concatenation. Never normalize citations or move them across extensions. In explicit custom-fields REPLACE mode, bypass specialization entirely.
- **Snapshots replace, unknown objects merge.** Copy non-null top-level custom-content state/form_schema objects wholesale (including explicit null properties), dropping stale keys. No snapshot rule for custom-fields namesakes.
- **Flow:** parsed OpenAI delta → extension-specific accumulator → defensive merged node → existing assembled response → response-column extraction / try-it-out body. No new exception fallback; invalid outer payloads keep current no-op behavior and JSON type transitions remain replacements.

## Risks / Trade-offs

- [Repeated complete text snapshots double content] → Apply concatenation only to documented delta text fields; do not infer snapshots from prefixes or deduplicate fragments.
- [Unknown string semantics remain ambiguous] → Keep legacy generic policy; losslessness for arbitrary protocols is not promised. Raw-event retention is separate work.
- [Annotation identity collisions] → Separate numeric/selector identities and reject invalid identity shapes; tests cover missing IDs, duplicates, interleaving, and independent extensions.
- [Mutation or null filtering loses data] → Deep-copy inputs/outputs and test explicit null properties in new objects and snapshots.
- [Snapshot replacement changes extracted values] → Intentional contract correction with tests; no stored-result rewrite.

## Migration Plan

Deploy normally; no migration or feature flag. Rollback restores previous assembly for new runs only. Run synthetic runner tests plus try-it-out unit/functional coverage, Spotless, and Checkstyle. Update runner pattern documentation and examples if their assembled values change; config/spec index need no change unless review finds their summaries materially inaccurate.
