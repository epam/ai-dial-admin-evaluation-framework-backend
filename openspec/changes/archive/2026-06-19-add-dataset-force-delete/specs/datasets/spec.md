## ADDED Requirements

### Requirement: Force delete dataset unbinds all referencing suites
`DELETE /api/v1/datasets/{id}` SHALL accept an optional `force` boolean query parameter (default `false`). When `force=true`, the system SHALL — within a single meta transaction — set `test_suites.dataset_id := NULL` for **every** suite referencing the dataset (regardless of count or dataset `visibility`), delete the dataset row, and cascade-delete the dataset's test cases via the existing FK; it SHALL return HTTP 204. The previously-bound suites SHALL remain alive in an unbound state. When `force` is omitted or `false`, the existing delete behavior SHALL be preserved unchanged: a `PUBLIC` dataset still referenced by one or more suites SHALL return HTTP 409 (RESTRICT), and a `PRIVATE` dataset SHALL unbind its single suite and delete as today.

#### Scenario: Force delete with one referencing suite
- **WHEN** client calls `DELETE /api/v1/datasets/{id}?force=true` on a dataset referenced by exactly one suite `S`
- **THEN** system SHALL respond with HTTP 204; the dataset row SHALL be removed; the dataset's test cases SHALL be removed (cascade); suite `S` SHALL still exist with `datasetId = null`

#### Scenario: Force delete with two referencing suites
- **WHEN** client calls `DELETE /api/v1/datasets/{id}?force=true` on a `PUBLIC` dataset referenced by two suites `S1` and `S2`
- **THEN** system SHALL respond with HTTP 204; the dataset row SHALL be removed; both `S1` and `S2` SHALL still exist with `datasetId = null`

#### Scenario: Default delete (force omitted) preserves RESTRICT
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` (no `force`, or `force=false`) on a `PUBLIC` dataset referenced by one or more suites
- **THEN** system SHALL respond with HTTP 409 and list the dependent suite names, exactly as in the existing RESTRICT behavior; no suite SHALL be unbound and the dataset SHALL NOT be deleted

#### Scenario: Force delete is atomic
- **WHEN** any step of the `force=true` delete path fails (unbind of any suite, test-case cascade, or the dataset delete itself)
- **THEN** the entire transaction SHALL roll back; the dataset, its test cases, and every suite's `dataset_id` SHALL all remain unchanged

#### Scenario: Force delete of unknown dataset
- **WHEN** client calls `DELETE /api/v1/datasets/{id}?force=true` for an unknown id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`
