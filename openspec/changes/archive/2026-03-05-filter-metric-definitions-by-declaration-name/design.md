## Context

The `test_suite_metric_definitions` table has an FK (`metric_declaration_id`) to `metric_declarations`. Currently, the TSMD list endpoint supports filtering only by `name` (the TSMD's own name). Users want to filter by the metric declaration's human-readable name to find all definitions backed by the same declaration (e.g. "Accuracy").

The existing repository layer uses single-table SELECTs exclusively — there are zero JOINs in any Postgres repository. The `WhereBuilder` and `FilterFieldDefinition` treat the `column` field as an opaque string, which means a table-qualified column like `metric_declaration.name` works without any infrastructure changes.

## Goals / Non-Goals

**Goals:**
- Add `metricDeclarationName` as a filterable field on the TSMD list endpoint (EQ, NE, CONTAINS)
- Expose `metricDeclarationName` in all TSMD responses (list, get-by-id, create, update)
- Keep changes scoped to the TSMD repository and its supporting types — no changes to shared filter/sort infrastructure

**Non-Goals:**
- Sorting by `metricDeclarationName` (not requested; can be added later)
- Filtering by `metricDeclarationVersionId` (not needed now)
- Generalizing JOIN support in `WhereBuilder` or other shared infrastructure

## Decisions

### Decision 1: Always-JOIN in TSMD repository queries

**Choice**: All SELECT queries in `PostgresTestSuiteMetricDefinitionRepository` (list, findById, findByIdAndTestSuiteId) will JOIN `metric_declarations metric_declaration` and SELECT `metric_declaration.name AS metric_declaration_name`.

**Rationale**: The FK `metric_declaration_id` is NOT NULL, so an INNER JOIN never filters out rows. The JOIN has negligible cost (FK-indexed, small cardinality). Using an always-on JOIN avoids conditional query construction complexity. The alternative — a correlated subquery in the filter column definition — was rejected because it's a hack (hides a subquery inside a "column name") and would not provide `metricDeclarationName` in responses.

**Alternatives considered**:
- *Conditional JOIN* (only when the filter is present): More complex query building for minimal performance gain. Doesn't help with populating `metricDeclarationName` in responses.
- *Correlated subquery in FilterFieldDefinition.column*: Zero infrastructure changes but semantically wrong — a "column" field holding a subquery. Also doesn't populate the field in responses.

### Decision 2: Table-qualify all column references in TSMD repository

**Choice**: After adding the JOIN, alias the main table as `metric_definition` and the joined table as `metric_declaration`, then qualify all column references:
- Base SELECT: `metric_definition.id`, `metric_definition.name`, etc.
- Hardcoded WHERE: `metric_definition.test_suite_id = :testSuiteId`
- Filter whitelist: existing `name` entry changes from `"name"` to `"metric_definition.name"`
- Sort whitelist: existing `name` entry changes from `"name"` to `"metric_definition.name"`, `created_at_ms` to `metric_definition.created_at_ms`
- New filter entry: `"metricDeclarationName"` maps to `"metric_declaration.name"`

**Rationale**: Both tables have a `name` column. Without table qualification, column references become ambiguous after the JOIN. Qualifying everything is the safest approach.

### Decision 3: Transient field on DB model

**Choice**: Add a `metricDeclarationName` field to the `TestSuiteMetricDefinition` data model. It's populated by the RowMapper from the JOIN result but not persisted (not included in INSERT/UPDATE SQL).

**Rationale**: The data model is a pure carrier. The RowMapper extracts it from `rs.getString("metric_declaration_name")` (column alias from `metric_declaration.name AS metric_declaration_name`). The mapper passes it through to the response DTO. This follows the existing pattern where the model carries all fields the RowMapper extracts.

### Decision 4: RowMapper uses column alias

**Choice**: The RowMapper reads `metric_declaration_name` from the result set (the alias assigned in the SELECT clause: `metric_declaration.name AS metric_declaration_name`).

**Rationale**: Using an alias avoids ambiguity when multiple tables have a `name` column. The RowMapper references the alias, not the raw column name.

## Risks / Trade-offs

- **[First JOIN precedent]** This introduces the first JOIN in the repository layer. Future developers might over-apply JOINs where simpler approaches suffice. Mitigation: The TSMD spec explicitly documents this as a cross-entity filter backed by an existing FK + index. The decision is scoped to one repository.
- **[Sort whitelist coupling]** Sort column values (`"metric_definition.name"`, `"metric_definition.created_at_ms"`) are now coupled to the table alias used in the repository's FROM clause. If the alias changes, sorts break. Mitigation: The alias is a static constant in the repository; the sort whitelist is also static. Both are in the data layer and reviewed together.
- **[Metric declaration deletion]** If a metric declaration were deleted while TSMDs reference it, the INNER JOIN would drop those TSMDs from results. However, the FK constraint (`fk_tsmd_metric_declaration`) prevents deleting a declaration that has referencing TSMDs, so this cannot happen.

## Open Questions

None — the approach is straightforward and scoped.
