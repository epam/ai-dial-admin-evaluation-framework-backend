# Tasks

This change documents an already-implemented capability, so most tasks verify that the shipped code
matches the spec and reconcile the existing `structured-query-model` spec.

## 1. Verify execution behavior against the spec

- [x] 1.1 Confirm `StructuredQueryController` exposes `POST /api/v1/queries/execute` returning
      `StructuredQueryResultDto { rows, totalCount }`, and `StructuredQueryService` routes by `entity`
      with a 400 (ValidationException) for an unknown entity.
- [x] 1.2 Confirm validation matches the spec: field resolution against the discovered schema, the
      closed function set, `in` array-of-literals, literal `value_type` parsing, offset≥0, cursor rejected,
      limit clamped to [100, 1000]; and confirm per-field capability flags / mode coherence / array
      homogeneity are intentionally NOT enforced.
- [x] 1.3 Confirm `StructuredQueryBuilder`/`FilterTranslator`/`ExprTranslator`/`JsonbFieldResolver`
      translate operators and JSONB paths as documented (parameterized, metric values numeric), and that
      `StructuredQueryExecutor` rethrows `BadSqlGrammarException`/`DataIntegrityViolationException` as
      `ValidationException` (400, not 500).
- [x] 1.4 Confirm `totalCount` is populated only for row-mode `include_total` queries; aggregate mode and
      default omit it.

## 2. Verify tests cover every requirement scenario

- [x] 2.1 Run unit tests:
      `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.StructuredQueryServiceTest" --tests "com.epam.aidial.evaluation.experimental.query.service.translate.*"`
      — all pass.
- [x] 2.2 Run functional tests (boots context, exercises controller → service → repositories → both
      datasources):
      `./gradlew test --tests "com.epam.aidial.evaluation.functional.tests.StructuredQueryExecuteFunctionalTests" --tests "com.epam.aidial.evaluation.functional.tests.EvalSummaryStructuredQueryFunctionalTests" --tests "com.epam.aidial.evaluation.functional.tests.TestSuiteStructuredQueryFunctionalTests"`
      — all pass, covering execution, dispatch, validation rejections, operator translation, and total count.
- [x] 2.3 Confirm no spec scenario lacks a corresponding test; add the missing test if a gap is found.

## 3. Spec reconciliation and index

- [x] 3.1 At archive time, run `/opsx:sync` to merge the delta into `openspec/specs/structured-query-model/spec.md`
      (MODIFIED: validation/allowlist, SQL translation, response envelope → Implemented; ADDED: query
      execution endpoint). NEVER hand-copy the delta into the main spec.
- [x] 3.2 Update the `structured-query-model` **Purpose** paragraph so it no longer describes validation,
      SQL translation, and response envelopes as Planned follow-ups (the prose change accompanies the sync).
- [x] 3.3 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy (done: the
      `structured-query-model` summary/status reflects that execution is now Implemented, narrower than the
      original vision).
- [x] 3.4 Confirm neither the delta spec nor the synced spec references the temporary demo pages, and that
      no requirement overstates the implementation (esp. the validation/allowlist and response-envelope scope).
