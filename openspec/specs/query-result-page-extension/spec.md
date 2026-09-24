# Query Result Page Extension

## Purpose

Defines how a structured-query result page may carry values that cannot come from the query's own SQL — derived after execution from another datasource or an external service — and the guarantees that keep such values from weakening the query contract.

## Requirements

### Requirement: A result page's key set is open
A row returned by `POST /api/v1/queries/execute` SHALL carry the projection's keys **plus zero or more extension-derived keys**. The key set SHALL NOT be treated as closed, equal to the projection, or equal to the entity's published schema. This SHALL hold for every entity, whether or not any extension currently applies to it.
Status: **Implemented**

#### Scenario: Row carries a key the projection did not request
- **WHEN** a `row` query runs against an entity for which an extension applies and produces a value
- **THEN** each row carries every projected key plus the extension-derived key, and the query succeeds

#### Scenario: Entity with no applicable extension is unchanged
- **WHEN** a `row` query runs against an entity that no registered extension claims
- **THEN** each row carries exactly the projection's keys and nothing else

### Requirement: Extension-derived keys are result-only
An extension-derived key SHALL NOT be accepted in `filter`, `sort`, `group_by` or `select`; referencing one there SHALL be rejected with HTTP 400 as an unknown field. It SHALL NOT appear in the entity's published schema at `GET /api/v1/queries/entities/schema/{name}`, which SHALL continue to publish exactly the entity's queryable field set.
Status: **Implemented**

#### Scenario: Derived key rejected in a filter
- **WHEN** a query references an extension-derived key in `filter`, `sort`, `group_by` or `select`
- **THEN** the request is rejected with HTTP 400 as an unknown field

#### Scenario: Derived key absent from schema discovery
- **WHEN** `GET /api/v1/queries/entities/schema/{name}` is called for an entity that carries an extension-derived key
- **THEN** the published field list contains only the entity's queryable fields and no entry for the derived key

### Requirement: Extension is additive and order-preserving
An extension SHALL only add keys to a row. It SHALL NOT remove or overwrite a key the row already carries, SHALL preserve the existing key order, SHALL omit a key whose value is unavailable rather than inserting null, and SHALL leave the page's total count unchanged. It SHALL always return a page — the input page unchanged is the correct "nothing to add" result — and SHALL NOT return null.
Status: **Implemented**

#### Scenario: Existing key wins over a derived key of the same name
- **WHEN** a row already carries a key an extension would add (for example via a client-supplied `select` alias)
- **THEN** the row's existing value is preserved and the extension does not overwrite it

#### Scenario: Unavailable value is omitted, not nulled
- **WHEN** an extension has no value for a given row
- **THEN** that row carries no key for it, rather than a key with a null value

#### Scenario: Total count is unaffected
- **WHEN** a query requesting a total count is extended
- **THEN** the reported total count is the value the query produced, unchanged by extension

### Requirement: An extension failure never fails the query
A query that executed successfully SHALL NOT fail because a derived value could not be added. If an extension raises an error, or returns nothing where a page is required, the system SHALL log it, return the page without that extension's contribution, and still apply every other registered extension. A misbehaving extension SHALL be contained identically however it misbehaves: the obligations on an implementation are not enforceable, so the coordinator — not the implementation — owns this guarantee.
Status: **Implemented**

#### Scenario: Extension error degrades to an unextended page
- **WHEN** an extension's lookup fails (for example its datasource is unavailable)
- **THEN** the response is HTTP 200 carrying the rows the query produced, without that extension's keys, and the failure is logged with its exception

#### Scenario: One failing extension does not suppress another
- **WHEN** two extensions apply to a page and the first fails
- **THEN** the second is still applied and its keys are present on the rows

#### Scenario: An extension returning no page is contained like a failure
- **WHEN** an extension returns null instead of a page, in violation of its contract
- **THEN** the response is HTTP 200 carrying the page as it stood before that extension, the violation is logged, every other registered extension is still applied, and no null page reaches the caller

## Implementation notes

- SPI: `query.service.repository.QueryResultPageExtender` — one method, `extend(StructuredQuery, QueryResultPage)`. Its javadoc is the source of truth for the five-clause implementation contract (additive, non-overwriting, order-preserving, omit-don't-null, never-null).
- Coordinator: `query.service.repository.JooqStructuredQueryExecutorExtender` — the `@Primary` `StructuredQueryExecutor`. Wraps the concrete package-private `JooqStructuredQueryExecutor` (not the interface, which would resolve to itself) and folds the page through `List<QueryResultPageExtender>` in bean order. Each extender runs in its **own** try/catch inside the loop, so one failure never suppresses a later extender; a `null` return is detected and contained on the same path as a throw. Deliberately carries no `@LogExecution` — its delegate has it, and annotating both logs every query twice.
- First implementation: `query.service.repository.OverallScoreTestSuiteRunsPageExtender` (see `test-suite-runs-query-entity`, requirement "Run-level overall score on a row result page").
- Second implementation: `query.service.repository.TotalCostTestSuiteRunsPageExtender` (see `test-suite-runs-query-entity`, requirement "Run-level total cost on a row result page", from `enrich-test-suite-runs-total-cost`). Conditional on `query-dsl.extension.test-suite-run.cost.enabled=true`. Its source value comes from an external dial-adas call it bounds itself: it captures the caller's `CallerCredential`/OpenTelemetry `Context` on the request thread, submits the lookup (`service.domain.BatchRunTotalCostLookup` with a dedicated short-timeout `DialAdasClient`) to a dedicated executor, and waits with an authoritative timed `Future.get`. Unlike the general coordinator-owned failure isolation this SPI otherwise relies on, this implementation catches every expected asynchronous outcome other than an on-time success (rejection, timeout, interruption, lookup failure) itself, logs each once with the exception last, and degrades to the page it received — the documented exception on `QueryResultPageExtender`'s javadoc to "SHOULD NOT swallow its own failures".
- Derived keys are kept out of the queryable surface by omission: they are absent from the entity's `bindings()` and from its `QueryableEntitySchemaProvider`, so the existing unknown-field path yields HTTP 400 with no special-casing.
- API contract: the key's only published description is the OpenAPI text on `query.service.dto.StructuredQueryResultDto#rows` and the `execute` operation in `query.web.StructuredQueryController`, since by design such a key appears in no schema.
- Tests: `JooqStructuredQueryExecutorExtenderTest` (no extenders, one adding keys, a throwing extender, a throwing extender not suppressing a later one, a null-returning extender, a null-returning extender not suppressing a later one), `OverallScoreTestSuiteRunsPageExtenderTest` (including query-level skips for a projection that does not key the run `id` as `id` or already carries the derived key, and a multi-row page proving row-order preservation), `TotalCostTestSuiteRunsPageExtenderTest` (applicability/id derivation/one batch call/merge mechanics, caller-credential and OpenTelemetry propagation across the async submission boundary, and the authoritative timed `Future.get` deadline with degrade-once failure handling on timeout/interruption/execution failure/rejected submission).
- Pattern doc: `docs/patterns/query-result-page-extension.md`.
