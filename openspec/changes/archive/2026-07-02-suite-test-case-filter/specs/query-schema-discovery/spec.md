## ADDED Requirements

### Requirement: `test_cases` is a complex queryable entity
The system SHALL register `test_cases` as a complex queryable entity so clients can discover its
field schema and query test cases through the Structured Query DSL. The entity's catalog item SHALL
have `complex=true` and `schemaIdField="dataset_id"`. Its instance-independent base schema SHALL be
derived from the generated `test_cases` jOOQ table (with the `data` JSONB field listed as-is). Its
instance-specific detailed schema SHALL require a `dataset_id` parameter and SHALL flatten the JSONB
`data` field into `data::<field>` entries derived from that dataset's test-case schema, mapping each
declared field type to a query field type and preserving array-typed fields as `array`. A detailed
schema request without `dataset_id`, or naming a dataset that does not exist, SHALL follow the schema
discovery error contract.
Status: **Planned**

#### Scenario: Catalog lists the test_cases complex entity
- **WHEN** `GET /api/v1/queries/entities` is called
- **THEN** the response lists `test_cases` with `complex=true` and `schemaIdField="dataset_id"`, in
  alphabetical order among the other entities

#### Scenario: Detailed schema flattens data fields for a dataset
- **WHEN** `GET /api/v1/queries/entities/schema/test_cases?dataset_id=<id>` is called for a dataset
  whose test-case schema declares fields `category` (string) and `tags` (array)
- **THEN** the response replaces the JSONB `data` field with flattened entries `data::category`
  (typed string) and `data::tags` (typed array), alongside the entity's base columns

An execute query over `test_cases` SHALL include a `dataset_id` equality filter (a `dataset_id = <uuid>`
comparison); the system uses it both to scope the returned rows to that dataset AND to type each
`data::<field>` binding (enabling array-aware `co`/`nc`). A query omitting the `dataset_id` filter, or
providing a non-UUID value, SHALL be rejected with HTTP 400.

#### Scenario: Ad-hoc query over test_cases executes
- **WHEN** a client `POST /api/v1/queries/execute` with `entity=test_cases`, a `dataset_id` equality
  filter, and a filter over `data::<field>` values
- **THEN** the system SHALL return the matching test-case rows scoped to that dataset

#### Scenario: Array-field CONTAINS over test_cases produces JSONB containment
- **WHEN** a client `POST /api/v1/queries/execute` with `entity=test_cases`, a `dataset_id` equality
  filter, and a `co` comparison on an array-typed `data::<field>`
- **THEN** the system SHALL translate `co` to JSONB element containment (consistent with the run path)
  and return the matching rows

#### Scenario: Execute over test_cases without dataset_id is rejected
- **WHEN** a client `POST /api/v1/queries/execute` with `entity=test_cases` but no `dataset_id`
  equality filter (or a non-UUID `dataset_id`)
- **THEN** the system SHALL respond with HTTP 400

## Implementation Notes
- `TestCasesSchemaProvider` (models `EvalSummariesSchemaProvider`); dataset schema loaded via
  `DatasetService`.
- `TestCaseQueryRepository` + `PostgresTestCaseQueryRepository` bind `test_cases` to the `TEST_CASES`
  table on `@Qualifier("metaDsl")`, delegating to `StructuredQueryExecutor`.
- Type-aware flattened bindings produced by `TestCaseFieldBindingsBuilder`.
