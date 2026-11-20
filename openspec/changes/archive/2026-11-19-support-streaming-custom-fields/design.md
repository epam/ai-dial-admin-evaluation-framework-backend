## Context

See `proposal.md` for motivation and `specs/eval-execution-engine/spec.md` for the behavior contract. `SseEventParser` already retains arbitrary JSON properties. The loss occurs in `StreamingResponseAccumulator.assembleOpenAiResponse`, which consumes only content and custom-content deltas. `CustomContentAccumulator` owns indexed attachments/stages and contains reusable recursive object merging.

All changes remain in the existing DB-free `evaluation-runner-core` `.job` package. These accumulators are fresh, per-response state holders, not Spring beans, matching the current custom-content and provider accumulators.

## Goals / Non-Goals

**Goals:**
- Separate extension-specific behavior from shared JSON merging, with minimal orchestration changes.
- Offer an explicit array merge policy while preserving legacy custom-content behavior exactly.
- Keep event payloads, accumulated state, and returned results isolated through defensive copies.

**Non-Goals:**
- No pluggable registry, annotation DTO hierarchy, Spring wiring, or application-level configuration.
- No whole-object replacement option: replacement is exclusively an array policy.
- No changes to mode detection, parser timing/size rules, other choices, or provider-specific partial responses.

## Decisions

### 1. Shared object accumulation with thin domain wrappers

Add package-private `JsonObjectDeltaAccumulator`, `CustomFieldsAccumulator`, and `ArrayMergeMode` in `com.epam.aidial.evaluation.runner.job`. The shared accumulator provides object accumulation, recursive merging into an existing object, and defensive snapshots. It supports `APPEND` and `REPLACE` array modes.

`CustomFieldsAccumulator` composes the shared accumulator, defaults to `APPEND`, and accepts `ArrayMergeMode` through an explicit constructor. `StreamingResponseAccumulator` uses the default constructor; the alternate constructor is an internal code-level option, not an exposed REST or application setting.

`CustomContentAccumulator` retains responsibility for indexed attachment/stage maps and unindexed lists. It delegates its ordinary scalar/object merging and merging of partial indexed elements to shared merging configured with `REPLACE`. Preserve its existing initial-copy paths, null handling, object deep-copy behavior, array output order, omission rules, and treatment of malformed indexed array values. Do not route indexed arrays through generic append behavior.

Alternatives considered:
- Last-object-wins copying: simpler but loses fields from earlier chunks.
- Independent custom-fields implementation: easy to localize but duplicates recursive merging.
- A policy registry/strategy framework: unnecessary for two extensions. A small enum plus domain wrappers is sufficient.

### 2. Policy applies recursively to arrays at object properties

For custom fields, object properties merge recursively; scalars and non-null type changes replace. Incoming null properties are ignored recursively, including within fresh objects and scalar-to-object replacements. Preserve explicitly supplied objects even when null filtering leaves them empty: `{"metadata":{"label":null}}` becomes `{"metadata":{}}`. Object elements within arrays remain atomic deep copies, including their null properties. This differs deliberately from legacy custom-content seeding: its initial object/indexed-element copies and newly introduced nested objects preserve supplied nulls, while subsequent property merges ignore null overwrites. Keep those initial-copy paths separate from custom-fields object construction when sharing recursive merging; the array policy alone does not define this distinction. With `APPEND`, array elements are copied in receipt order, without deduplication or merging element objects; with `REPLACE`, an incoming array replaces the previous array, even when empty. The policy applies to nested object properties as well as top-level arrays. An initial empty array is a supplied field and is preserved, whereas an empty top-level extension contributes no fields.

Return defensive snapshots so changing either source events or returned objects cannot alter future results. Do not expose live references to internal array or object nodes.

### 3. Extract the delta once and keep assembly orchestration small

For each JSON event in OpenAI mode, obtain `choices[0].delta` once using one guarded extraction helper. Read text, `custom_content`, and `custom_fields` from it and feed independent accumulators. Preserve the current content extraction behavior for existing inputs.

On successful parsing, build the normal message and attach each non-empty extension independently. On non-success parsing, retain the existing JSON-string text-only response and status propagation. The accumulator's serialization error handling continues to set `ERROR` and log the caught Jackson exception as the last SLF4J argument.

Interaction flow: SSE stream → existing parser → parsed events → existing mode detection → first-choice delta → text/custom-content/custom-fields accumulation → assembled message → existing response-column extraction. Try-out callers using `assemble(SseParseResult)` receive the same assembly behavior.

### 4. No persistence or API machinery changes

The observable output adds optional `choices[0].message.custom_fields`. There are no new endpoints, DTO fields, database columns, migrations, transactions, security checks, external integrations, or configuration properties. No new dependencies are required; use existing Jackson tree types.

### 5. Incremental verification

First extract shared merging with compatibility tests, then implement custom-fields accumulation and its policy tests, then connect assembly and make the existing synthetic regression pass. Tests cover nested objects/arrays, scalar overwrites, type transitions, null ignoring, duplicate/null array elements, empty append/replacement, omission, and defensive copies. Assembly tests cover both extensions together and non-success parsing. Execute the runner suite and module formatting/Checkstyle checks before marking verification complete.

Update the runner package inventory and relevant streaming documentation. This follows existing architecture and tooling, so no `openspec/config.yaml` change is needed. The spec index needs no new entry because the capability already exists; change its summary only if it becomes materially inaccurate.

## Risks / Trade-offs

- [Shared extraction changes custom-content semantics] → Keep indexed bookkeeping and initial-copy behavior in its existing wrapper; run all existing custom-content tests and add targeted compatibility cases before integration.
- [Append duplicates snapshot-style annotations] → Deliberately preserve duplicates by default; callers handling snapshots can explicitly select replacement.
- [Defensive copies increase allocation] → Follow existing accumulator ownership conventions; avoid duplicate copies within a single merge where ownership is already established.
- [Append produces a larger assembled object] → Retain existing parser size limits; do not introduce an unbounded side channel or new storage. No new size-accounting contract is introduced.
- [Replacement is internal-only] → Document its scope; exposing a suite/API setting would be a separate change with configuration and contract requirements.

## Migration Plan

Deploy with the normal application build after runner tests and checks pass. No migration or runtime configuration is required. Rollback removes the optional assembled extension while retaining existing content/custom-content behavior; historical response bodies are not rewritten.
