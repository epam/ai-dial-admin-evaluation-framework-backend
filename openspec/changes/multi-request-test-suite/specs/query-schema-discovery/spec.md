## MODIFIED Requirements

### Requirement: Instance-specific detailed schema for complex entities
The system SHALL expose, at `GET /api/v1/queries/entities/schema/{name}/detailed`, the flat schema of
a complex entity for a concrete instance, replacing the flattenable JSONB fields of the base schema
with per-instance field families. For `eval_summaries`, the instance is a test suite run and the
schema SHALL be derived from that run's frozen snapshot: `data:<field>` families from the snapshot's
test-case schema, `response:<column>` families from the snapshot's **chain-union** response column set
— the union of the snapshot's flat `responseColumns` and every `additionalRequests` element's
`responseColumns`, in chain order — and per metric of the run's latest computation a numeric
`metric:<name>:<field>` family plus an opaque object `metricInfo:<name>`. The instance SHALL be
selected by `test_suite_run_id` (the advertised `schemaIdField`); when only `test_suite_id` is
supplied the suite's latest run SHALL be used.

Because response column names are unique across the chain, the `response:<column>` family requires no
request qualification. A given result row populates only the columns owned by its own chain request,
so a queried `response:<column>` field is null on rows produced by other chain requests.
Status: **Planned**

#### Scenario: Detailed schema derived from the run snapshot
- **WHEN** `GET /api/v1/queries/entities/schema/eval_summaries/detailed?test_suite_run_id=<runId>` is
  called for a run whose snapshot defines a test-case field `question`, a response column `answer`,
  and a metric `Accuracy`
- **THEN** the response includes `data:question`, `response:answer`, `metric:Accuracy:<field>`, and
  `metricInfo:Accuracy`, and does not include the unflattened `test_case_data`, `extracted_columns`,
  `metric_values`, or `metric_infos` fields

#### Scenario: Suite id resolves the latest run
- **WHEN** the detailed schema is requested with only `test_suite_id=<suiteId>`
- **THEN** the schema is derived from that suite's most recent run's snapshot

#### Scenario: Run without a snapshot is rejected
- **WHEN** the detailed schema is requested for a run that has no suite snapshot (a legacy run created
  before the snapshot model)
- **THEN** the request is rejected with a validation error and no schema is returned

#### Scenario: Chain response columns are advertised as one union
- **WHEN** the detailed schema is requested for a run whose snapshot chain declares `session_id` on
  request 0 and `answer` on request 1
- **THEN** the response includes both `response:session_id` and `response:answer`, in chain order,
  with no request qualification in the field names

#### Scenario: Single-request run advertises the same fields as before
- **WHEN** the detailed schema is requested for a run of a single-request suite
- **THEN** the chain union equals the snapshot's flat `responseColumns` and the advertised fields are
  identical to those produced before this capability existed

#### Scenario: Frozen chain governs the advertised schema
- **WHEN** a run's snapshot froze a two-request chain and the live suite has since grown to four
  requests
- **THEN** the detailed schema for that run advertises only the two frozen requests' response columns

## ADDED Requirements

### Requirement: Request identity fields are queryable on eval_summaries
The `eval_summaries` base schema SHALL advertise `request_index` and `request_label` as queryable
fields, derived from the generated table metadata like every other non-JSONB column, so queries MAY
filter, sort, and project on which chain request produced a row.
Status: **Planned**

#### Scenario: Request fields appear in the base schema
- **WHEN** the base schema for `eval_summaries` is requested
- **THEN** it includes `request_index` (numeric) and `request_label` (string)

#### Scenario: Query filters by chain request
- **WHEN** a structured query filters `eval_summaries` on `request_label` equal to `invoke`
- **THEN** only rows produced by the request labeled `invoke` are returned

## Implementation notes

`EvalSummariesSchemaProvider` — the `response:` family is sourced from the shared chain-union
response-column helper (also used by `EvalSummaryExportColumnPlanner`) instead of the snapshot's flat
`responseColumns`. The base schema requires no code change for the new columns: it is derived from the
generated jOOQ table, so `request_index` and `request_label` are picked up once the migration and
`./gradlew generateJooq` have run.
