# Tasks

This change documents an already-implemented capability, so most tasks verify that the shipped code
matches the spec rather than build new code.

## 1. Verify discovery surface against the spec

- [x] 1.1 Confirm `QuerySchemaController` (`experimental.query.web`) exposes `GET /api/v1/queries/entities`,
      `/entities/schema/{name}`, and `/entities/schema/{name}/detailed` with OpenAPI annotations matching the
      documented contract.
- [x] 1.2 Confirm `QueryEntityRegistry` lists entities alphabetically, returns 404 (EntityNotFoundException)
      for unknown entities, and 400 (ValidationException) for a detailed request against a simple entity.
- [x] 1.3 Confirm `JooqTableSchemaResolver` derives the base schema from the generated jOOQ table with the
      documented type inference (`VARCHAR(36)`→uuid, JSONB→object/array) and fails fast on unmapped types.
- [x] 1.4 Confirm `EvalSummariesSchemaProvider` resolves the detailed schema from the run snapshot
      (`test_suite_run_id` preferred, `test_suite_id`→latest run) and rejects a null-snapshot run with a
      ValidationException; `TestSuitesSchemaProvider` exposes no detailed schema.

## 2. Verify tests cover every requirement scenario

- [x] 2.1 Run unit tests: `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.*"`
      (EvalSummariesSchemaProviderTest, QueryEntityRegistryTest, JooqTableSchemaResolverTest,
      TestSuitesSchemaProviderTest) — all pass.
- [x] 2.2 Run functional tests (boots context, exercises controller → services → both datasources):
      `./gradlew test --tests "com.epam.aidial.evaluation.functional.tests.QuerySchemaDiscoveryFunctionalTests"`
      — all pass, covering catalog, base schema, detailed schema (run id and suite id), and the 404/400 error
      contract.
- [x] 2.3 Confirm no spec scenario lacks a corresponding test; add the missing test if a gap is found.

## 3. Docs and spec index

- [x] 3.1 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy (done: index lists the new
      `query-schema-discovery` folder with status and a one-sentence summary).
- [x] 3.2 Decide at archive time whether the `QueryableEntitySchemaProvider` SPI + `QueryEntityRegistry`
      warrants an AGENTS.md "Unique Patterns" / "Inline conventions" entry; update AGENTS.md per its Maintenance
      guidelines if so.
- [x] 3.3 Confirm the spec contains no reference to the temporary demo pages.
