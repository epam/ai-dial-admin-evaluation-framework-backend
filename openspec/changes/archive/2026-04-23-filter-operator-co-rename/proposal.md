## Why

The filter DSL uses `contains` as an operator token, which is verbose relative to all other operators (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`). Aligning it with the short-name convention produces a more consistent API. Additionally, `eq`/`ne` comparisons on string fields are currently case-sensitive (PostgreSQL `=`), which is surprising for a search/filter UX where users expect case-insensitive matching by default.

## What Changes

- **BREAKING** Rename filter operator token `contains` → `co` in the HTTP query param API (e.g., `filter=name:co:test`)
- **BREAKING** Rename `FilterOperator.CONTAINS` → `FilterOperator.CO` throughout the codebase
- Make `eq`/`ne` comparisons on STRING fields case-insensitive (`lower(column) = lower(:param)`)
- Update `QueryParamDescriptionGenerator` to emit `co` in generated OpenAPI operator tables and examples
- Update `entity-filtering` spec to reflect new operator name and eq/ne case-insensitivity rule

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `entity-filtering`: Operator `contains` renamed to `co` (breaking); `eq`/`ne` on STRING fields become case-insensitive.

## Impact

- **API (breaking)**: Any client sending `filter=field:contains:value` will receive HTTP 400 after this change.
- **Code**: `FilterOperator.java`, `WhereBuilder.java`, `FilterParserTest.java`, `QueryParamDescriptionGenerator.java`, and all tests that reference the string `"contains"` in filter parameters.
- **OpenAPI docs**: Operator table and examples auto-regenerated; no manual OpenAPI example files need changes.
- **No DB migration required.**
- **No config changes required.**
