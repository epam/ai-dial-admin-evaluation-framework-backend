## ADDED Requirements

### Requirement: Imported results carry optional request identity
The result import item DTO SHALL accept an optional `requestIndex` (integer, `>= 0`, defaulting to `0` when omitted) and an optional `requestLabel` (string, max 255). Both SHALL be persisted verbatim onto the imported row. `requestIndex` participates in the row's natural key, so importing two rows for the same test case, run index, and turn index with distinct `requestIndex` values SHALL persist both.
Status: **Planned**

#### Scenario: Omitted request fields default
- **WHEN** an import item omits `requestIndex` and `requestLabel`
- **THEN** the persisted row SHALL have `request_index = 0` and a null `request_label`, preserving compatibility for existing importers

#### Scenario: Supplied request fields are persisted
- **WHEN** an import item supplies `requestIndex: 2` and `requestLabel: "invoke"`
- **THEN** the persisted row SHALL carry those values

#### Scenario: Distinct request indices coexist
- **WHEN** two import items share `testCaseId`, `runIndex`, and `turnIndex` but differ in `requestIndex`
- **THEN** both rows SHALL persist, distinguished by `request_index` in the natural key

#### Scenario: Negative request index rejected
- **WHEN** an import item supplies a negative `requestIndex`
- **THEN** the request SHALL be rejected with HTTP 400 `VALIDATION_ERROR`

### Requirement: requestLabel is client-supplied and not validated against a snapshot
`requestLabel` SHALL be taken verbatim from the import payload and SHALL NOT be derived from, or cross-validated against, any suite snapshot. `requestIndex` SHALL NOT be bounded by a snapshot's chain length. This preserves the import path's support for results produced by **external** test suite runs, which have no snapshot chain from which a label could be derived or against which an index could be bounded.
Status: **Planned**

#### Scenario: External run supplies its own labels
- **WHEN** results from an external run are imported with arbitrary `requestLabel` values and no corresponding suite chain exists
- **THEN** the labels SHALL be persisted as supplied and the import SHALL succeed

#### Scenario: Request index beyond any chain length is accepted
- **WHEN** an import item supplies a `requestIndex` larger than the target run's snapshot chain length, or the run has no snapshot
- **THEN** the import SHALL succeed and the value SHALL be persisted unchanged

#### Scenario: Label inconsistent with suite config does not break consumers
- **WHEN** an imported row carries a `requestLabel` that differs from the suite's configured label at that index
- **THEN** the row SHALL persist and downstream consumers SHALL remain functional, because `request_label` is display-only and is not part of the natural key

## Implementation notes

`TestCaseRunResultItemDto` gains `requestIndex` (`@Min(0)`) and `requestLabel` (`@Size(max = 255)`); the batch-write persistence path passes both through to `test_case_run_results`. Mirrors the existing optional `turnIndex`/`totalTurns` handling, which likewise applies no cross-validation.
