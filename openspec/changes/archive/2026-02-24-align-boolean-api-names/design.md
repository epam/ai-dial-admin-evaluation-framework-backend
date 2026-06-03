## Context

Boolean fields on Lombok `@Data` classes with primitive `boolean` type generate `isX()` getters. Jackson serializes these as `x` (stripping the `is` prefix), but filter/sort whitelist keys, CSV headers, PATCH body keys, and query params were written using the Java getter convention (`isValid`, `isEnabled`). This creates a mismatch between the JSON response contract and other API surfaces.

Current state:
- JSON response: `"valid": true`, `"enabled": true`
- Filter param: `filter=isValid:EQ:true`, `filter=isEnabled:EQ:true`
- Sort param: `sort=isValid,asc`, `sort=isEnabled,asc`
- CSV column header: `isEnabled`
- CSV export query param: `includeIsEnabled`
- PATCH body key: `"isEnabled"`

## Goals / Non-Goals

**Goals:**
- Align all API surface names for boolean fields to match the JSON serialization output (`valid`, `enabled`)
- Maintain a single naming convention across filter, sort, CSV, PATCH, and response DTOs

**Non-Goals:**
- Changing Java field names or Lombok getter conventions — the Java `isX()` style is fine internally
- Changing DB column names (`is_valid`, `is_enabled`) — these are internal and don't leak to clients
- Changing SQL named parameter keys (`:isValid`, `:isEnabled`) — these are internal JDBC bindings

## Decisions

### Decision 1: Rename filter/sort keys to match JSON property names

Filter and sort whitelist keys change from `isValid`/`isEnabled` to `valid`/`enabled`.

**Why**: The JSON response is the primary API contract. Filter/sort keys should match field names in that contract so clients can programmatically construct filters from response fields.

**Alternative considered**: Add `@JsonProperty("isValid")` to DTOs to make JSON match Java. Rejected because it would break all existing response consumers, and the `is`-less convention is standard Jackson behavior.

### Decision 2: Rename CSV column header to match JSON property names

CSV export/import header changes from `isEnabled` to `enabled`.

**Why**: Consistent naming across JSON and CSV formats. Clients processing both formats should see the same field names.

### Decision 3: Rename PATCH body key to match JSON response

PATCH merge patch key changes from `"isEnabled"` to `"enabled"`.

**Why**: RFC 7396 JSON Merge Patch semantics — patch keys should match the target document's field names. The target document (response DTO) uses `enabled`.

### Decision 4: Rename CSV export query parameter

`includeIsEnabled` → `includeEnabled`.

**Why**: Aligns with the renamed CSV column and JSON property name.

## Risks / Trade-offs

- **[Breaking change]** → All four renames are breaking for existing API consumers. Mitigation: this is a pre-GA project; coordinate with FE team on the rename.
- **[CSV backward compatibility]** → Existing CSV files with `isEnabled` header will fail import after this change. Mitigation: acceptable for pre-GA; if needed in future, CSV import could accept both headers.
