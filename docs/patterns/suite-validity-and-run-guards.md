# Suite validity = config only

`isValid` reflects configuration correctness only — template + bindings + endpoint schema for DEPLOYMENT suites, and additionally `toolRef.inputSchema` required-argument coverage for `MCP_TOOL` suites (`McpArgumentValidator`). Test-case presence is **not** a component of stored suite validity and does not affect `isValid` or `validationWarnings` in the suite GET response. `SuiteValidationService.validateSuite(...)` is config-only; calling it on test-case mutations is not needed and must not be done.

Test-case presence is enforced at **run-creation time** only: `TestSuiteRunService.createRun` guard #4 counts runnable test cases via `RunnableTestCaseSelector.countRunnable(datasetId, filterJson)` and throws `InvalidOperationException("Suite has no valid and enabled test cases")` (→ 409 `INVALID_OPERATION`) when count is zero.

Guard order:

1. not-found
2. unbound
3. config-invalid
4. zero-runnable
5. rate-limits

Test-case **data** validation (`test_cases.is_valid`) is owned by the test-case domain + Phase 1 and is **never** triggered from suite validation.
