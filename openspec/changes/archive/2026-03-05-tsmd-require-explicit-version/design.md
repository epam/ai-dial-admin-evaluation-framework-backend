## Context

A Test Suite Metric Definition (TSMD) binds a metric declaration to a test suite with parameter bindings. Currently, the backend auto-resolves `metricDeclarationVersionId` to the latest `MetricDeclarationVersion` (by greatest `schema_version`) on both create and update. The client never supplies a version — the request DTO has no `metricDeclarationVersionId` field.

This causes a usability problem: when a new metric declaration version is published, the backend silently upgrades the TSMD to target it, even though the user's parameter bindings were designed for an older version's schema. The user may not know the latest version's schema, leading to binding/version mismatches.

## Goals / Non-Goals

**Goals:**
- Make `metricDeclarationVersionId` a required field in the TSMD request DTO (both create and update)
- Remove automatic latest-version resolution from the service layer
- Validate that the supplied `metricDeclarationVersionId` belongs to the referenced `metricDeclarationId`
- Keep the change minimal — no DB schema changes, no new packages

**Non-Goals:**
- Changing the response DTO shape (already includes `metricDeclarationVersionId`)
- Adding a "list versions for declaration" endpoint (already exists or out of scope)
- Migrating existing TSMDs to pin their current versions (they already store the version)
- Removing `findLatestByMetricDeclarationId()` from the repository — it's used by `MetricProviderSyncService` and `MetricDeclarationService`

## Decisions

### D1: Add `metricDeclarationVersionId` as required field in request DTO

**Decision**: Add `@NotNull UUID metricDeclarationVersionId` to `TestSuiteMetricDefinitionRequestDto`.

**Rationale**: This is the simplest approach — clients already receive `metricDeclarationVersionId` in GET responses, so they know which version a TSMD uses. For new TSMDs, the client can list metric declaration versions and choose one. Making it `@NotNull` ensures validation at the API boundary via Bean Validation.

**Alternative considered**: Making the field optional and falling back to latest resolution when absent. Rejected because it preserves the exact problem we're solving — silent upgrades.

### D2: Replace `resolveLatestVersion()` with `validateVersionBelongsToDeclaration()`

**Decision**: In `TestSuiteMetricDefinitionService`, remove the `resolveLatestVersion()` call and add a validation step: `existsByIdAndMetricDeclarationId(versionId, declarationId)`. If the version doesn't exist or doesn't belong to the declaration, throw `EntityNotFoundException`.

**Rationale**: We need to ensure data integrity — a client can't supply a version ID from a different metric declaration. This is a simple existence check, not a full entity fetch.

**Implementation**:
- Add `existsByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId)` to `MetricDeclarationVersionRepository` interface
- Implement in `PostgresMetricDeclarationVersionRepository` with a `SELECT 1` query
- In the service, replace `resolveLatestVersion()` with validation call; pass `dto.getMetricDeclarationVersionId()` directly to the mapper

### D3: Simplify mapper signatures

**Decision**: The mapper's `toEntity()` and `update()` methods currently accept `metricDeclarationId` and `metricDeclarationVersionId` as separate parameters (resolved server-side). After this change, both come from the DTO directly, so we simplify to pass the DTO fields.

**Rationale**: The mapper receives `metricDeclarationVersionId` from the request DTO now. The `metricDeclarationId` is also from the DTO. The separate parameters remain for `testSuiteId` (from path) but the version/declaration IDs now flow naturally from the DTO.

### D4: Remove `validateMetricDeclarationExists()` as separate step

**Decision**: The new `existsByIdAndMetricDeclarationId()` check implicitly validates that the declaration has at least one version, and the FK constraint on the version table ensures the declaration exists. We can remove the separate `validateMetricDeclarationExists()` call.

**Rationale**: If the version exists and belongs to the declaration, the declaration must exist. This reduces one DB round-trip. The error message changes slightly: instead of "Metric declaration not found" vs "No versions found", we get a single "Metric declaration version not found" message. This is acceptable since the client is now explicitly providing the version.

## Risks / Trade-offs

- **[Breaking API change]** → Clients must update to supply `metricDeclarationVersionId`. Mitigated by: the field value is already available in TSMD GET responses, and clients can list versions via the metric declarations API.
- **[Error message change]** → The separate "declaration not found" vs "no versions found" errors collapse into a single "version not found or doesn't belong to declaration" error. → Acceptable trade-off for simpler code.
