## Context

The `requestTemplate` system uses `${{variable}}` and `${{variable:default}}` placeholder syntax to mark variable parts of a request. Placeholder extraction is performed by `TemplateVariableExtractor` (`service.domain`) using a single regex:

```
\$\{\{([^:}]+)(?::([^}]*))?\}\}
```

Group 1 = name (`[^:}]+`), Group 2 = default (`[^}]*`). Extracted variables are collected into `ExtractedVariable` records (name, sources, hasDefault, defaultValue) and later assembled into `TemplateVariableDto` (adds binding, inferredType, resolvedValue) by the variables convenience API.

**Problem**: There is no way to attach a type hint directly to a placeholder in the template body. `TemplateVariableDto.inferredType` is populated through a priority chain (endpoint schema → binding+testCaseSchema → STRING fallback), but this requires a binding to exist and a testCaseSchema field to be typed. Before any binding is configured — or when a variable is bound via `constantValue` — the type is always STRING, so FE cannot render the correct input control (e.g., a file picker for `FILE` type variables).

## Goals / Non-Goals

**Goals:**
- Allow authors to embed a type hint directly in a placeholder: `${{name|type}}` and `${{name|type:default}}`.
- Update `TemplateVariableExtractor` to parse the new `|type` segment and expose `declaredType` on `ExtractedVariable`.
- Update `TemplateVariableDto` to expose `declaredType` (from placeholder) and rename `inferredType` → `effectiveType` (highest-priority resolved type).
- Update the type inference chain to use `declaredType` as the highest-priority source.
- Formally document `FILE` type support for `responseColumns`.

**Non-Goals:**
- Adding type hints to `requestBodySchema` or `responseBodySchema` JSON Schema objects.
- Metric evaluation materialization for `FILE`-typed response columns (deferred).
- Validation that a `FILE`-typed column's extracted value actually looks like a DIAL path.

## Decisions

### Decision 1: `|` as type-hint separator

`|` is preferred over `::` because it does not create an edge-case with defaults: after splitting on the first `|`, the remainder is `type` or `type:default`. The default (everything after the first `:` in the remainder) is unrestricted and can contain `|`, `::`, or any other character.

**Alternative considered**: `::` — also unambiguous with the name constraint, but defaults containing `::` (e.g. PostgreSQL cast syntax `id::uuid`) produce a leading-colon artefact when the type segment contains a `::` before the default separator `:`, requiring more careful parsing. `|` avoids this entirely.

### Decision 2: Name constraint enforced in regex

Variable names must match `[a-zA-Z0-9_]+`. In practice the current regex group `[^:}]+` already excluded `:` and `}`; we additionally exclude `|`. The updated name group is `[^:|}]+`. This makes the first `|` after the name always the unambiguous type separator.

**Note**: The current codebase never produced names containing `|` in practice — this constraint is codifying existing real-world usage.

### Decision 3: `declaredType` + `effectiveType` replace `inferredType`

`TemplateVariableDto.inferredType` is renamed to `effectiveType` (additive rename — the JSON field name changes from `inferredType` to `effectiveType`). A new `declaredType` field is added. The distinction:

- `declaredType`: nullable; populated only when the placeholder explicitly declares a type (`${{var|file}}`). Exposed so FE/clients can distinguish "author declared" from "inferred".
- `effectiveType`: non-null; the resolved type using priority: **(1) declared** → **(2) endpointRef schema** (requestBodySchema or parameter definition) → **(3) binding+testCaseSchema** → **(4) STRING fallback**.

**Note on renaming**: This is a non-breaking additive change for consumers who only use `effectiveType`. Consumers reading `inferredType` from the old API will get null after the rename; any such clients should be updated.

### Decision 4: Unknown type hint produces a soft validation warning (not HTTP 400)

If the author writes `${{doc|unknowntype}}` and `unknowntype` does not map to a `SchemaFieldType` value, the extractor treats it as an unrecognised hint and emits a soft validation warning on the TestSuite (same mechanism as other template warnings). The variable is still extracted with `declaredType = null`.

**Alternative considered**: Hard-reject with HTTP 400. Rejected — soft validation is consistent with how all other template issues (missing bindings, unmatched URLs) are handled. Users can save and fix iteratively.

### Decision 5: `ExtractedVariable` record gains `declaredType` field

`TemplateVariableExtractor.ExtractedVariable` (the internal record) is extended with `declaredType: SchemaFieldType` (nullable). This keeps the parsed type co-located with the extracted variable and avoids re-parsing downstream.

### Decision 6: Regex update

Old regex:
```
\$\{\{([^:}]+)(?::([^}]*))?\}\}
```

New regex (3 capture groups):
```
\$\{\{([^:|}]+)(?:\|([^:}]+))?(?::([^}]*))?\}\}
```

- Group 1: name `[^:|}]+`
- Group 2 (optional): type keyword `[^:}]+` (after `|`)
- Group 3 (optional): default `[^}]*` (after `:`)

`extractFromString()` must be updated to read group 2 as type, group 3 as default (previously groups 1/2).

## Risks / Trade-offs

- **`inferredType` → `effectiveType` rename is technically breaking** for JSON API clients reading that field name. Risk is low because `TemplateVariableDto` is only returned from the `/template-variables` convenience endpoints, which are not part of a widely-distributed contract. Mitigation: add `@JsonAlias("inferredType")` on `effectiveType` for one release cycle if needed.
- **`|` in existing variable names**: The current regex allowed `|` in names (it was not excluded). If any stored template contains `${{a|b}}` today, it would have been stored with name `a|b`. After the change, `a` is the name and `b` is the type hint. This could misparse existing data. Mitigation: scan persisted templates before deploying; in practice this pattern does not occur.
