## MODIFIED Requirements

### Requirement: File reference rewriting
The system SHALL rewrite all suite-scoped DIAL file references in JSONB fields from `@ef/suites/{sourceId}/` to `@ef/suites/{newId}/` using string replacement. This applies to **suite-level** fields — `inputBindings`, `requestTemplate`, `argumentTemplate` — to the **request chain** field `additionalRequests`, covering every chain entry's own `requestTemplate`, `argumentTemplate` and `inputBindings` regardless of suite type, and to TSMD fields (`configBindings`, `inputBindings`). Rewriting the chain SHALL NOT depend on `suiteType`: an `MCP_TOOL` chain's per-entry `argumentTemplate` file references SHALL be rewritten exactly as a `DEPLOYMENT` chain's per-entry `requestTemplate` references are. Test-case-level data and overrides are NOT rewritten by clone (test cases are not copied; they remain in the shared dataset and reference their dataset-scoped paths unchanged — see the file-reference-scheme follow-up referenced in the change's design.md).
Status: **Planned**

#### Scenario: File refs in suite-level bindings are rewritten
- **WHEN** source suite has an input binding with `constantValue: "@ef/suites/aaa/config.json"`
- **THEN** cloned suite SHALL have the binding with `constantValue: "@ef/suites/bbb/config.json"`

#### Scenario: File refs in a chain entry's argument template are rewritten
- **WHEN** source suite is `MCP_TOOL` and its `additionalRequests[0].argumentTemplate` references `@ef/suites/aaa/contract.pdf`
- **THEN** the clone's `additionalRequests[0].argumentTemplate` SHALL reference `@ef/suites/bbb/contract.pdf`

#### Scenario: File refs in a chain entry's request template are rewritten
- **WHEN** source suite is `DEPLOYMENT` and its `additionalRequests[1].requestTemplate` references `@ef/suites/aaa/doc.pdf`
- **THEN** the clone's `additionalRequests[1].requestTemplate` SHALL reference `@ef/suites/bbb/doc.pdf`

#### Scenario: File refs in a chain entry's input bindings are rewritten
- **WHEN** a chain entry has an input binding with `constantValue: "@ef/suites/aaa/config.json"`
- **THEN** the cloned entry SHALL carry `constantValue: "@ef/suites/bbb/config.json"`

#### Scenario: File refs in TSMD bindings are rewritten
- **WHEN** source TSMD has a `configBindings` or `inputBindings` entry with `constantValue: "@ef/suites/aaa/metric-config.json"`
- **THEN** cloned TSMD SHALL have the binding with `constantValue: "@ef/suites/bbb/metric-config.json"`

#### Scenario: Non-file-ref strings are not affected
- **WHEN** a JSONB field contains a string that does not match the `@ef/suites/{sourceId}/` pattern
- **THEN** that string SHALL remain unchanged after cloning

#### Scenario: Test case file refs are NOT rewritten
- **WHEN** test cases under the source's dataset have `data` entries referencing `@ef/suites/{sourceId}/...` (legacy paths) or `@ef/datasets/{datasetId}/...` (future paths)
- **THEN** clone SHALL NOT rewrite those references (test cases are not copied — they remain owned by the dataset shared between source and clone)

## Implementation Notes

- The chain field was already copied to the clone by the multi-request capability; what this change adds is that its **per-entry** template fields participate in file-reference rewriting, which previously applied only to the suite-level `requestTemplate`/`argumentTemplate`/`inputBindings`. Without it, a cloned MCP chain would keep pointing at the source suite's files.
- Clone continues to enforce the full chain-wide hard validation against the effective post-override suite, which now includes the entry-shape-vs-`suiteType` rule owned by `multi-request-suite`.
