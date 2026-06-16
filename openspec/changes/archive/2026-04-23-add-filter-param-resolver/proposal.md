## Why

Spring MVC's default `@RequestParam List<String>` binding splits single parameter values on commas via `StringToCollectionConverter`. This conflicts with the `field:in:a,b,c` syntax added in the recently archived `add-filter-in-operator` change: a request like `?filter=testCaseName:in:Delete1,Delete2` arrives at the controller as `["testCaseName:in:Delete1", "Delete2"]`, and `FilterParser` fails to interpret `"Delete2"` as a valid filter expression. The functional test `TestCaseTests.shouldBulkDeleteByInFilterWithTwoNames` was failing in CI for this reason, and the same latent bug affects any filter value that legitimately contains a comma (e.g. `name:eq:hello,world`).

A short-term heuristic (`recombineIfSpringTokenized` in `FilterParser`, mirroring `SortParser`) was applied to stop the CI regression, but it is a symptom-level fix that silently changes the semantics of genuinely malformed multi-filter requests and cannot handle all edge cases (IN values containing multiple colons, mixed operators). The root cause is at the HTTP binding layer and should be fixed there.

## What Changes

- Introduce `@FilterParam` annotation (target: method parameters) and `FilterParamArgumentResolver` (implements `HandlerMethodArgumentResolver`) in the `web.pagination` package, which reads raw `request.getParameterValues("filter")` and bypasses Spring's comma-splitting conversion entirely.
- Register the resolver via a new `WebMvcConfiguration` (or extend an existing one) so it is applied globally.
- Migrate all `@RequestParam(name = "filter", …) List<String> filter` parameters across controllers (~13 sites in 9 controllers) to `@FilterParam(max = …) List<String> filter`.
- Preserve `@Size`-equivalent validation via the annotation's `max` attribute; the resolver enforces the cap and raises the existing validation error shape.
- **Remove** `FilterParser.recombineIfSpringTokenized` and its associated tests — the heuristic becomes unnecessary and potentially misleading once binding is exact.
- Update the `entity-filtering` spec to document the exact-binding contract: commas inside a filter value are preserved literally; multiple filters are only possible via repeated `?filter=` query parameters.

All changes above are **Planned**. No DB, config-property, or external-contract changes are required; the API surface for legitimate repeated-parameter usage is unchanged.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `entity-filtering`: the filter query parameter is bound from the raw HTTP parameter values without comma tokenization; commas inside a value are always preserved literally, and multiple filter conditions MUST be sent as repeated `?filter=` parameters.

## Impact

- **Code**: new `@FilterParam` annotation + `FilterParamArgumentResolver` + `WebMvcConfigurer` registration in `web.pagination`; `FilterParser.recombineIfSpringTokenized` removed; ~13 controller parameter annotations swapped.
- **Tests**: new unit tests for the resolver (single value with commas, repeated params, size cap enforcement); `FilterParserTest` entries for `recombineIfSpringTokenized` removed; the previously failing functional test `TestCaseTests.shouldBulkDeleteByInFilterWithTwoNames` should pass without parser-level heuristics.
- **API**: no breaking change for documented usage. Clients relying on the accidental `?filter=a,b` (single parameter, comma-split) behavior would need to switch to `?filter=a&filter=b` — this was never a documented contract.
- **OpenAPI**: `OpenApiQueryParamCustomizer` keys off the parameter name, not the annotation type, so no generator change is expected; verify descriptions still render for `filter` params.
- **Risk**: minor risk of overlooked controller sites or OpenAPI regression; covered by the test plan.
- **Rollout**: single merge; no feature flag; the change is internally visible and safe to revert via annotation swap if needed.
