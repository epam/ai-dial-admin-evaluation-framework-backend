## ADDED Requirements

### Requirement: Response reference resolution spans the suite's request chain

TSMD soft validation SHALL resolve `Response`-sourced binding references against the **suite-wide union** of response columns — the suite's own `responseColumns` plus every `additionalRequests[i].responseColumns` — so a binding to a column declared on any request in the chain resolves cleanly and does NOT produce an `UNRESOLVED_REFERENCE` warning. A reference that matches no column anywhere in the chain SHALL continue to produce `UNRESOLVED_REFERENCE`. Because response-column names are globally unique across the chain, resolution SHALL remain by bare name with no request qualifier and no ambiguity.

Automatic revalidation of a suite's TSMDs SHALL be triggered when the **union** of response columns changes on a suite update — including when a column is added to, removed from, or renamed within any `additionalRequests[i].responseColumns`, or when an entry is added to or removed from `additionalRequests` — and SHALL NOT be triggered by a change to an additional request that leaves the union unchanged (for example an edit to its `urlTemplate`, `headers` or `inputBindings`). The revalidation itself SHALL be performed with the post-update union.

Status: **Implemented**

#### Scenario: Binding to an additional request's column resolves
- **WHEN** a TSMD binds a metric parameter to the response column `answer`, declared on `additionalRequests[0]`
- **THEN** validation SHALL NOT emit `UNRESOLVED_REFERENCE` for that binding

#### Scenario: Binding to a nonexistent column still warns
- **WHEN** a TSMD binds a metric parameter to a response column declared on no request in the chain
- **THEN** validation SHALL emit `UNRESOLVED_REFERENCE` for that binding and the TSMD SHALL be marked invalid

#### Scenario: Adding a column to an additional request triggers revalidation
- **WHEN** `PUT /api/v1/test-suites/{id}` adds a response column to `additionalRequests[0]`
- **THEN** the suite's TSMDs SHALL be revalidated synchronously against the new union

#### Scenario: Removing an additional request triggers revalidation
- **WHEN** an update removes an `additionalRequests` entry that declared response columns
- **THEN** the suite's TSMDs SHALL be revalidated, and any TSMD binding to one of the removed columns SHALL become invalid with `UNRESOLVED_REFERENCE`

#### Scenario: Non-column chain edits do not trigger revalidation
- **WHEN** an update changes only an additional request's `urlTemplate`, leaving every request's `responseColumns` untouched
- **THEN** TSMD revalidation SHALL NOT be triggered
