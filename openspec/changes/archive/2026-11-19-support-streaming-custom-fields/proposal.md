## Why

OpenAI-compatible SSE payloads can carry `choices[0].delta.custom_fields`, including annotations, but response assembly currently drops this field despite the SSE parser preserving it. Supporting it allows response columns and consumers to access the extension through the assembled non-streaming message.

## What Changes

- Preserve and accumulate `delta.custom_fields` into `choices[0].message.custom_fields` in OpenAI chat-completions mode.
- Recursively merge objects, retain the latest non-null scalar, and append arrays in receipt order by default, without schema-specific assumptions or deduplication.
- Provide a code-level array replacement option; incoming arrays replace earlier arrays, including empty arrays clearing them. Objects continue to merge.
- Share recursive JSON merging between custom-fields and custom-content accumulators while retaining all existing custom-content semantics.
- Planned classes in the existing runner `.job` package: `JsonObjectDeltaAccumulator`, `CustomFieldsAccumulator`, and `ArrayMergeMode`.
- Non-goals: parser changes, additional choices, annotation validation, application configuration, API request options, and changes to other provider modes or partial-response handling.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `eval-execution-engine`: OpenAI streaming assembly preserves custom fields with defined object/scalar/array semantics and an explicit code-level array replacement mode.

## Impact

- Affects `evaluation-runner-core` response assembly and unit tests; backend and CLI consumers inherit the assembled response change without separate wiring.
- Response bodies gain an optional `message.custom_fields` field. No endpoint, DTO, database schema, migration, security, configuration, or dependency changes.
- Current state: parser preservation and custom-content merging are implemented; a synthetic custom-fields regression test has been added and executed, failing because the assembled field is absent. Production support is planned.
- Main risk: accidentally changing existing custom-content array behavior during shared-merge extraction. Preserve indexed attachments/stages and replacement of other arrays, with existing tests as regression coverage.
- Rollout: additive support with append as the normal assembly default; replacement is available to code-level accumulator callers only.
- Test plan: run the existing failing regression, add focused merge/replacement/null/immutability tests, execute the runner suite, and run formatting and Checkstyle checks. Test fixtures must be synthetic, not copied from `tmp/events.json`.
