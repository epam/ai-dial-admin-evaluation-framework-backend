# best-practices Specification

## Purpose

Formal, versioned home for project-wide architectural rules referenced from `AGENTS.md`. Where `AGENTS.md` keeps one-line Do's and Don'ts for quick scanning, this spec gives each rule its full requirement text, rationale, and review scenarios so reviewers and contributors share the same enforceable standard.

Phase 1 introduces the cross-domain service-access rule (codified after refactoring `DatasetService` to stop injecting `TestSuiteRepository` / `TestCaseRepository`). Subsequent phases will extend the same rule to ~13 other services flagged in the audit and may add further code-quality rules referenced by `AGENTS.md` (imports over FQNs, config defaults in YAML only, constants per bounded context, no duplicated logic) as they are formalised.

## Requirements

### Requirement: Cross-domain access goes through services, not foreign repositories

A domain service SHALL inject only its own domain's repository. To read or write data owned by another domain, the service MUST call that domain's service. Direct injection of a foreign domain's repository into a service is forbidden.

"Domain" is the bounded context the service is named after — e.g., `DatasetService` owns the dataset domain (`DatasetRepository`); `TestSuiteService` owns the test-suite domain (`TestSuiteRepository`); `TestCaseService` owns the test-case domain (`TestCaseRepository`). A service MAY inject sibling services from other domains as needed.

Rationale: cross-domain business rules (e.g., "cannot rebind a suite bound to a PRIVATE dataset", "removing a schema field must prune orphan keys from all test cases in the dataset") belong inside the owning domain's service. Reaching past a sibling service into its repository smears those rules across service boundaries, defeats encapsulation, and forces every consumer to re-implement the guards. Routing through the sibling service centralises the rule in one place and makes the call site obvious in code review.

When the cross-domain need is read-only and a full DTO is wasteful, the owning service MAY expose a narrow projection method (returning only the fields the caller needs) rather than the full response DTO. The principle is the same: the call goes service-to-service.

Status: **Implemented** for `DatasetService` (phase 1). Phase 2 / 3 extends the rule to the remaining services flagged in the audit (`TestSuiteService`, `TestCaseService`, `RevalidationService`, `CsvImportService`, `CsvExportService`, `ZipExportService`, `TestSuiteRunService`, `TestSuiteCloneService`, `TestSuiteMetricDefinitionService`, `FileService`, `ResolvedRequestService`, `TryItOutService`).

#### Scenario: Service binds an entity that lives in another domain

- **WHEN** `DatasetService.create` needs to set `datasetId` on a `TestSuite` (after creating a PRIVATE dataset bound via `bindToSuiteId`)
- **THEN** it calls `testSuiteService.bindDataset(suiteId, newDatasetId)` — it does NOT inject `TestSuiteRepository` and call `findById`/`save` itself

#### Scenario: Service needs a read-only listing of cross-domain entities

- **WHEN** `DatasetService.delete` needs to list test suites that reference a PUBLIC dataset (for the HTTP 409 dependency listing and the post-delete race re-check)
- **THEN** it calls `testSuiteService.getReferencingDataset(datasetId)` — it does NOT inject `TestSuiteRepository.findSuitesReferencingDataset`

#### Scenario: Service needs a bulk mutation on a sibling domain's data

- **WHEN** `DatasetService.update` drops a field from a dataset's `testCaseSchema` and must prune that key from every test case in the dataset
- **THEN** it calls `testCaseService.removeDataFields(datasetId, removedFields)` — it does NOT inject `TestCaseRepository.removeDataFields`

#### Scenario: Sibling service does not yet expose the needed method

- **WHEN** a service needs a cross-domain operation that the owning service does not yet expose
- **THEN** the operation SHALL be added as a method on the owning service first (with the right transactional annotation and any necessary guards), and only then called from the caller — the caller MUST NOT bypass the owning service by injecting its repository

#### Scenario: Code review catches a forbidden foreign-repo injection

- **WHEN** a reviewer sees a `@RequiredArgsConstructor`-wired service field whose type is `<OtherDomain>Repository`
- **THEN** the review SHALL block the PR and ask the author to route the access through the owning domain's service; the reviewer MAY accept the violation only if a corresponding follow-up task is filed against the owning service to add the missing API

#### Scenario: Transactional boundaries when nesting service calls

- **WHEN** an outer `@Transactional("metaTransactionManager")` service method calls a sibling `@Transactional("metaTransactionManager")` service method
- **THEN** the inner call joins the outer transaction via Spring's REQUIRED propagation (no new physical transaction is started), and the shared `TransactionTimestampContext` remains consistent — the rule does NOT require any extra propagation configuration when both services use the same transaction manager

#### Scenario: Cross-datasource service call (meta ↔ analytics)

- **WHEN** a meta-domain service needs to read or write data in the analytics datasource (e.g., looking up a run result, persisting an analytics record from a meta-side flow)
- **THEN** it calls an analytics-domain service (which carries `@Transactional("analyticsTransactionManager")`) — it does NOT inject an analytics repository. Because the two transaction managers do not federate, the analytics-side write MUST be designed to be idempotent or recoverable from a partial state, since the outer meta transaction can succeed while the inner analytics call fails (or vice versa)
