## Why

`PageRequest` (in `.data.db`) currently contains parsing logic for web query parameters (`parseSortParams(...)`), including Spring MVC binding quirks. This couples MVC concerns to a DB-layer model, makes the pagination model harder to reuse, and complicates tests (parsing can’t be easily mocked/replaced).

## What Changes

- Move sort query parameter parsing out of `com.epam.aidial.evaluation.data.db.model.pagination.PageRequest`.
- Introduce a dedicated parsing component in a higher layer (web/service), e.g. `SortParamParser`, responsible for converting incoming `sort` query params into a structured `List<PageRequest.Sort>`.
- Update controllers / request-mapping code to:
  - Accept raw `sort` query parameters (repeatable `sort`).
  - Use the parser to produce structured sort orders.
  - Construct `PageRequest` using the structured sort list (e.g., `PageRequest.of(page, size, sort)`).
- Keep SQL safety rules where they belong:
  - Field allowlisting and mapping to SQL columns remain enforced in the data-access layer (`PageRequestSqlBuilder`/`PageRequestParams`).
- Update tests to mock/replace the parsing component where helpful (unit/controller tests), while keeping data-access tests focused on SQL building and allowlisting.

Non-goals:
- No functional API changes are intended; sorting behavior and validation must remain aligned with `openspec/specs/sorting/spec.md`.

## Capabilities

### New Capabilities

<!-- None (refactor only). -->

### Modified Capabilities

<!-- None (no spec-level behavior change intended). -->

## Impact

- Affected code:
  - `PageRequest` (remove parsing helpers; remain a pure pagination + structured sorting carrier)
  - Web layer controllers that accept `sort` query params (use parser + construct `PageRequest`)
  - Unit/functional tests that currently depend on `PageRequest.parseSortParams(...)`
- Cross-cutting:
  - Improves separation of concerns (MVC parsing vs DB pagination model)
  - Improves testability (parser can be mocked/replaced)
