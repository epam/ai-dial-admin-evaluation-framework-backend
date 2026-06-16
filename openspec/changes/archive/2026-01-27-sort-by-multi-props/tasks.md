## 1. Sorting contract (API-level)

- [x] 1.1 Define a repeatable `sort` query parameter format (`sort=<field>[,<asc|desc>]`) for list endpoints
- [x] 1.2 Implement parsing/normalization into a structured multi-key sort model (ordered list of keys)
- [x] 1.3 Add request validation + consistent HTTP 400 error for malformed sort values and unknown sort fields

## 2. Data access layer (safe multi-column ORDER BY)

- [x] 2.1 Extend shared pagination/sorting SQL helpers to build an `ORDER BY` from multiple validated sort keys
- [x] 2.2 Implement an allowlist mapping (API field -> SQL column/expression) mechanism per repository
- [x] 2.3 Add deterministic tie-breaker behavior (append `id ASC` when not present and applicable)

## 3. Apply to TestSuites list endpoint

- [x] 3.1 Update `GET /api/v1/test-suites` to accept multi-key sorting while preserving existing defaults when `sort` is absent
- [x] 3.2 Add/extend repository query for listing suites to apply multi-column sorting safely via allowlist
- [x] 3.3 Document supported sort fields for TestSuites in OpenAPI annotations (and align with specs)

## 4. Tests

- [x] 4.1 Add functional test coverage for single-key and multi-key sorting on TestSuites list
- [x] 4.2 Add functional test coverage for unknown sort fields and invalid directions (expect HTTP 400)
- [x] 4.3 Add regression test to ensure pagination stability (no item “flapping” across pages with ties)

