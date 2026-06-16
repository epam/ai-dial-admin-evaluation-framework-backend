## Why

On TSMD update, the backend currently auto-resolves `metricDeclarationVersionId` to the latest `MetricDeclarationVersion`. This forces users to provide parameter bindings compatible with the latest version — even if the older version's schema still supports evaluation. Users may not even know what the latest version expects, leading to mismatches between bindings and the resolved version. Requiring an explicit version in the request gives users full control over which metric declaration version they target.

## What Changes

- **BREAKING**: `metricDeclarationVersionId` becomes a **required** field in the TSMD create and update request DTO. Clients must always supply the version they intend to use.
- Remove server-side auto-resolution of the latest `MetricDeclarationVersion` on both create and update. The `resolveLatestVersion()` call in `TestSuiteMetricDefinitionService` is eliminated.
- The supplied `metricDeclarationVersionId` is validated to exist and belong to the referenced `metricDeclarationId`; mismatch returns HTTP 400.
- Response DTO continues to include `metricDeclarationVersionId` (no change).

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `test-suite-metric-definitions`: Remove automatic latest-version resolution on create/update. `metricDeclarationVersionId` becomes a required request field validated against the referenced metric declaration.

## Impact

- **API**: Breaking contract change — `TestSuiteMetricDefinitionRequestDto` gains a new required field `metricDeclarationVersionId`. All existing clients must be updated to supply it.
- **Service layer**: `TestSuiteMetricDefinitionService.resolveLatestVersion()` and its usage in `create()`/`update()` are removed. A new validation step ensures the provided version belongs to the given metric declaration.
- **Mapper**: `TestSuiteMetricDefinitionMapper` no longer receives the resolved version as a separate parameter; it maps `metricDeclarationVersionId` directly from the request DTO.
- **Repository**: `MetricDeclarationVersionRepository.findLatestByMetricDeclarationId()` may become unused and can be removed if no other callers exist.
- **Spec**: `openspec/specs/test-suite-metric-definitions/spec.md` requirements around version resolution change (create and update scenarios).
- **Tests**: Functional tests for create/update must be updated to supply `metricDeclarationVersionId` in requests and verify the new validation error when version doesn't belong to the declaration.
- **No DB/migration changes** — the `metric_declaration_version_id` column already exists as NOT NULL FK.
- **No config changes**.
