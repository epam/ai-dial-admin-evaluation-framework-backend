## 1. FILE Validation — Blank String False Positive Fix

- [x] 1.1 In `TestCaseValidationService.validateFileFields`: extend the null guard to also skip blank strings — `if (value == null || value.toString().isBlank()) { continue; }` — with updated comment explaining both cases
- [x] 1.2 In `TestCaseValidationService` required-field check loop: extend the null condition for `SchemaFieldType.FILE` fields to also match blank strings — produce "field is missing from data" warning with `REQUIRED` code when value is null or blank
- [x] 1.3 In `TestCaseValidationService` data-vs-binding check loop: before the loop (around line 104), build `Map<String, SchemaFieldType> fieldTypeByName` from `safeSchema`; extend the null condition at line ~114 from `value == null` to `value == null || (fieldTypeByName.get(binding.getDataField()) == SchemaFieldType.FILE && value instanceof String s && s.isBlank())` — scoped to FILE fields only; the warning message and code remain unchanged ("Required field '...' is empty in data", `REQUIRED`)

## 2. FILE Validation — Unit Tests

- [x] 2.1 In `TestCaseValidationServiceFileTest` (or equivalent unit test class): add scenario — optional FILE field with `""` → no warning
- [x] 2.2 Verify existing test `fileFieldWithNullValue_noWarning()` in `TestCaseValidationServiceFileTest` covers optional FILE field with `null` → no warning (regression guard; no new test needed, just confirm it still passes after Task 1.1)
- [x] 2.3 Add scenario — required FILE field with `""` → `REQUIRED` warning "field is missing from data"
- [x] 2.4 Verify existing test `requiredFileFieldMissing_producesRequiredWarning()` in `TestCaseValidationServiceFileTest` covers required FILE field with `null` → `REQUIRED` warning (regression guard; no new test needed, just confirm it still passes after Task 1.2)
- [x] 2.5 Add scenario — required FILE field with valid file ref → no warning (regression guard)
- [x] 2.6 Add scenario — optional FILE field with `"   "` (whitespace-only) → no warning (covers `isBlank()` not just `isEmpty()`)
- [x] 2.7 Add scenario — required FILE field with `"   "` (whitespace-only) → `REQUIRED` warning "field is missing from data"
- [x] 2.8 Search `src/test/java` for functional tests that create test cases with empty string FILE fields (via `MetaTestDataHelper` CSV import or direct data map construction). If any found, verify their validation assertions match the new behavior: required FILE field with `""` → `REQUIRED` warning; optional FILE field with `""` → no warning
- [x] 2.9 Add scenario — required binding (no default) + FILE-typed field + `""` in data → `REQUIRED` warning "Required field '...' is empty in data" (data-vs-binding path)
- [x] 2.10 Add scenario — required binding (no default) + FILE-typed field + `"   "` (whitespace-only) in data → `REQUIRED` warning (data-vs-binding path)
- [x] 2.11 Add scenario — required binding (no default) + STRING-typed field + `""` in data → no warning (confirms blank-as-absent is FILE-only; regression guard for non-FILE types)
- [x] 2.12 Add scenario — optional binding (variable has default) + FILE-typed field + `""` in data → no warning (default covers the missing value)

## 3. Metric Evaluation — Per-Result Timeout (rename + split)

- [x] 3.1 In `MetricEvaluationProperties`: rename field `cancellationGracePeriodMs` → `perResultTimeoutMs`; update `@Min` constraint (keep 1000); update `@ConfigurationProperties` binding from `cancellation-grace-period-ms` to `per-result-timeout-ms`
- [x] 3.2 In `MetricEvaluationContext`: rename field `cancellationGracePeriodMs` → `perResultTimeoutMs` (the Lombok `@Builder` field rename causes the generated builder method name to change from `.cancellationGracePeriodMs(...)` to `.perResultTimeoutMs(...)`)
- [x] 3.3 In `InProcessMetricEvaluationExecutor.evaluateAndBuild()`: replace `context.getCancellationGracePeriodMs()` with `context.getPerResultTimeoutMs()` (two occurrences: the `.get(timeout)` call and the log message)
- [x] 3.4 In `TestSuiteEvaluationJob.buildMetricEvaluationContext()`: update the full builder call from `.cancellationGracePeriodMs(metricEvaluationProperties.getCancellationGracePeriodMs())` to `.perResultTimeoutMs(metricEvaluationProperties.getPerResultTimeoutMs())` — both the builder method name AND the getter name change after Tasks 3.1 and 3.2
- [x] 3.5 Search all usages of `MetricEvaluationContext.builder()...cancellationGracePeriodMs(` in `src/main` and `src/test` and rename every occurrence to `.perResultTimeoutMs(`. The primary call site is `TestSuiteEvaluationJob` (already covered by Task 3.4); this task is a safety check to catch any other build sites. Note: `MetricEvaluationWorkerTest` builder calls do NOT currently set this field — they compile cleanly after the rename and do not need modification. Do NOT rename `EvaluationContext` builder calls — those belong to Phase 1 and are out of scope for this change.
- [x] 3.6 In `application.yml`, under the `metric-evaluation:` block specifically (NOT under `test-suite-run.execution:` which also has a `cancellation-grace-period-ms`): replace `cancellation-grace-period-ms: 30000` with `per-result-timeout-ms: ${METRIC_EVAL_PER_RESULT_TIMEOUT_MS:150000}`
- [x] 3.7 In `docs/configuration.md`: (a) remove the row for `metric-evaluation.cancellation-grace-period-ms`; (b) add a new row for `metric-evaluation.per-result-timeout-ms` with description "Max wall time (ms) to wait for all TSMD futures on a single TestCaseRunResult before cancelling remaining futures and marking timed-out TSMDs as FAILED", default `150000`, env var `METRIC_EVAL_PER_RESULT_TIMEOUT_MS`; (c) add a migration note immediately below the `metric-evaluation` configuration table (before the next section heading) as a bold callout, stating: `metric-evaluation.cancellation-grace-period-ms` is removed; any YAML or env var setting this property will produce a Spring Boot unknown-property warning at startup with no effect — migrate to `metric-evaluation.per-result-timeout-ms` / `METRIC_EVAL_PER_RESULT_TIMEOUT_MS`

## 4. Metric Evaluation — Per-Result Timeout Unit Tests

- [x] 4.1 In `InProcessMetricEvaluationExecutorTest` (create if absent): add scenario — all TSMD futures complete within `perResultTimeoutMs` → EvalSummary assembled from actual TSMD responses without interruption
- [x] 4.2 In `InProcessMetricEvaluationExecutorTest`: add scenario — one or more TSMD futures do NOT complete within `perResultTimeoutMs` → executor cancels remaining futures, records timed-out TSMDs as FAILED errors in EvalSummary, and continues to next result (use `@ExtendWith(MockitoExtension.class)`; stub `worker.evaluate()` to block, set `perResultTimeoutMs` below that threshold)

## 5. Spec Sync

- [x] 5.1 Sync delta spec to `openspec/specs/file-ref-validation/spec.md` via `/opsx:sync` — adds blank-string and whitespace-only scenarios for optional and required FILE fields
- [x] 5.2 Sync delta spec to `openspec/specs/metric-evaluation/spec.md` via `/opsx:sync` — replaces "Graceful cancellation" requirement with "Cancellation with hard shutdown", adds per-result timeout requirement, updates "Batch write failure" scenario and "All properties with defaults" scenario
