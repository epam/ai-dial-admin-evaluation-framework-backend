## 1. API-boundary sort parsing component

- [x] 1.1 Introduce `SortParamParser` (or similarly named) Spring component in web/service layer to convert raw `List<String>` `sort` params into `List<PageRequest.Sort>`, preserving current behavior (including Spring “split token” recombination)
- [x] 1.2 Add unit tests for the parser covering: blank values, invalid direction, too many tokens, multi-key precedence order, and Spring tokenization edge case
- [x] 1.3 Add/adjust web exception mapping so parser errors return HTTP 400 with a clear message (align with `openspec/specs/sorting/spec.md`)

## 2. Refactor `PageRequest` to be a pure carrier

- [x] 2.1 Remove `PageRequest.parseSortParams(...)` and any MVC-specific parsing logic from `com.epam.aidial.evaluation.data.db.model.pagination.PageRequest`
- [x] 2.2 Ensure `PageRequest` constructors/factories support structured sort list (`PageRequest.of(page, size, List<PageRequest.Sort>)`) and remain immutable/copy-safe where needed
- [x] 2.3 Update any call sites/tests that referenced `parseSortParams(...)` to use the new parser component

## 3. Wire parsing into controllers/services

- [x] 3.1 Update list endpoints that support sorting (e.g., `TestSuiteController` and any others) to accept repeatable `sort` query parameter and delegate parsing to `SortParamParser`
- [x] 3.2 Ensure controllers build `PageRequest` using structured `sort` list and keep SQL allowlisting/mapping enforced in data-access (`PageRequestSqlBuilder`/`PageRequestParams`)
- [x] 3.3 Validate behavior remains consistent with multi-key sorting requirements (order precedence, default direction, HTTP 400 on malformed values)

## 4. Tests and regression coverage

- [x] 4.1 Update/extend functional tests for affected endpoints to cover multi-key sorting and invalid sort input (HTTP 400) without depending on DB-layer parsing
- [x] 4.2 Keep data-access tests focused on SQL generation + allowlisting (no parsing assumptions); adjust `PageRequestSqlBuilderTest` inputs if needed to use structured sorts

## 5. Quality gates and cleanup

- [x] 5.1 Run `checkstyleMain` and `checkstyleTest` and fix any violations introduced by the refactor
- [x] 5.2 Remove dead code paths and update any relevant docs/comments referencing `PageRequest.parseSortParams(...)`
