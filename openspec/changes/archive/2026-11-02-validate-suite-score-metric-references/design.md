## Context

See proposal.md. Suite validity is `warnings.isEmpty()` (`SuiteValidationService`); warnings are persisted as JSON on `test_suites` via `TestSuiteRepository.updateValidation`. `SuiteValidationService.validateSuite(TestSuite, schema)` routes through `SuiteDefinition`, which carries no score fields. `TestSuiteMetricDefinitionService` already loads the owning suite (create/update) and owns `TestSuiteMetricDefinitionRepository`. `TestSuiteCloneService` validates the clone before inserting it, then copies every source TSMD with its name unchanged. `roc_auc` takes two operands and does its own `array_agg` (`RocAucFunction`); `convertValue(expression, StructuredQuery.class)` already works for it (`OverallScoreDefinitionResolver`).

## Goals / Non-Goals

**Goals:** one small validator reused by full suite validation and by TSMD mutations; refresh after TSMD mutation touches only score warnings.

**Non-Goals:** full suite revalidation on TSMD mutation; generic Query DSL traversal; write-time rejection; locking/retry for concurrent writers.

## Decisions

1. **`ScoreReferenceValidator`** (new, `service.domain`, `@Component`, no DB; deps `JsonbMapper`, `ObjectMapper`):
   `List<ValidationWarningDto> validate(TestSuite suite, Set<String> metricNames)` — reads `overallScore` / `testCaseOverallScore` JSON columns itself.
   - Unreadable column (`JsonbMapper` throws `IllegalArgumentException`) → `UNSUPPORTED_SCORE_FUNCTION` for `overallScore`; ignored for `testCaseOverallScore`.
   - `WeightedMean` → check each `weights[i].metricName` (skip null names).
   - `CustomFunction` in `overallScore` → `convertValue(..., StructuredQuery.class)`; supported iff `select.size() == 1` and `select[0].expr` is `FnExpr("roc_auc")` with 2 args. For each `FieldExpr` arg starting with `metric::`: `rest = name.substring(prefix.length())`; TSMD name = `rest` up to `rest.lastIndexOf("::")`, or `rest` when absent. Conversion failure or other shape → one `UNSUPPORTED_SCORE_FUNCTION`.
   - `CustomFunction` in `testCaseOverallScore` (legacy; rejected at write) → ignored.
   - Never throws. Reuse `METRIC_COLUMN_PREFIX` / `COLUMN_SEPARATOR`; path roots and `roc_auc` as constants in the class.
   - *Rejected:* generic JSON walker over all DSL branches (v1) — large, drift-prone, unnecessary while roc_auc is the only supported function.

2. **Full validation** — `SuiteValidationService`:
   - `withScoreReferences(ValidationResult base, TestSuite suite, Set<String> metricNames)` (new): appends `ScoreReferenceValidator` warnings; `valid = warnings.isEmpty()`. Existing template/binding validation calls stay unchanged at every call site.
   - Suite create: `withScoreReferences(validateSuite(dto, null, schema), entity, ∅)`; update: same with names of suite `id`.
   - `validateSuite(TestSuite, schema)` (existing, dataset revalidation): wraps its result with names from `TestSuiteMetricDefinitionService.findNamesByTestSuiteId(id)`, or empty when `id == null`.
   - Names come from the TSMD service (new method over new repository read `findNamesByTestSuiteId` — `SELECT name`, returned as `HashSet`). No bean cycle: TSMD service does not depend on `SuiteValidationService`.

3. **Partial refresh** — new `SuiteScoreWarningsRefresher` (`service.domain`, suite domain; deps `TestSuiteRepository`, `ValidationWarningsSerializer`, `ScoreReferenceValidator`, `TransactionTimestampContext`):
   `void refresh(TestSuite suite, Set<String> metricNames)` — deserialize stored warnings, drop those with path prefix `$.overallScore.` / `$.testCaseOverallScore.`, append fresh; if the resulting list and validity equal the stored ones, return; else `updateValidation(id, warnings.isEmpty(), json, timestampContext.getTimestamp())`.
   - Caller passes suite and names it owns → refresher touches no foreign repository.
   - Only `@Transactional` callers (TSMD service), so the transaction timestamp is available.

4. **TSMD create/update/delete** call the refresher after the repository write, in the same `@Transactional("metaTransactionManager")`, with `repository.findNamesByTestSuiteId(suiteId)`. Delete loads the suite first (currently it does not). The refresh call sits outside the `try` that maps `DataIntegrityViolationException` to the duplicate-name error.

5. **Clone**: pre-insert validation wraps the existing DTO validation in `withScoreReferences(base, entity, sourceNames)` with `sourceNames = testSuiteMetricDefinitionService.findNamesByTestSuiteId(sourceId)` — the clone receives exactly these names, so the result (and the clone response) is correct immediately. No post-copy refresh.

6. **Endpoint `$ref` fix**: restore `EndpointSchemaRefResolver` (`refStack.remove(ref)` in `finally`, after recursing) from `stash@{0}` and `EndpointSchemaRefResolverTest` from `stash@{0}^3` (it was untracked). Separate commit; mention in PR description.

7. **API surface**: new enum value `ValidationWarningCode.UNSUPPORTED_SCORE_FUNCTION`. One-sentence `@Schema` note on `TestSuiteResponseDto.validationWarnings`. Update only `api-v1-test-suites-id-GET-response-200-full.json`.

## Risks / Trade-offs

- [Existing suites with a non-roc_auc custom function become invalid (unrunnable) on next revalidation] → accepted; explicit warning code.
- [Concurrent suite update/clone and TSMD mutation can leave stale score warnings] → accepted; next validation recomputes.
- [Corrupt stored warnings JSON deserializes to `[]`, so a refresh drops non-score warnings and may flip `valid` to true] → accepted; next full validation restores them.
- [Path-prefix ownership of score warnings] → unit test asserting non-score warnings survive a refresh.

## Migration Plan

No DB/config change. Existing suites keep stored validity until their next validation trigger. Rollback = revert.
