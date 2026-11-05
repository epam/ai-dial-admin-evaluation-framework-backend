## Context

See `proposal.md` for the motivation (issue #288). The relevant flow today:

- **Export.** `TestCaseController` → `ZipExportService.hasFileFields(datasetId)`; only a `FILE` field forces a ZIP
  (CSV + `manifest.json` + `files/`). A `SKILL`-only schema exports as plain CSV with the cells verbatim. This stays as is.
- **Import** (`CsvImportService.previewCsv` / `importCsv`, both streaming over `CSVRecord`s):
  1. Per record, when schema auto-detection applies (`shouldAutoDetectSchema`: OVERRIDE, or an empty schema), or for new
     columns in MERGE, `updateInferredTypes` / `updateInferredTypesForNewFields` fold each non-blank cell's
     `inferCellType(...)` into a per-column `Map<String, SchemaFieldType> inferredTypes`, combining through
     `widenType(current, cell)`.
  2. After streaming, `CsvSchemaFieldBuilder.buildFromBindings` / `buildMergeDelta` turn `inferredTypes` into
     `FieldDefinitionDto`s with this precedence: manifest-declared field (tier 0) > `fileColumns` hint → `FILE` > inferred
     type > `STRING` default.
  3. Post-persist fixup (`fixupSingleTurnCase` / multi-turn variant) coerces cells of type-changed columns through
     `SchemaTypeCoercer`, and re-validates only rows whose data actually changed.
- **ZIP cell rewriting** (`ZipImportColumnTypeResolver` → `ZipCsvFileRefRewriter`) runs before `CsvImportService` and
  decides from the dataset schema and the manifest only, never from `inferredTypes`. Rewriting only touches whole-cell
  `files/…` values.
- `SkillRefResolver` (runner-core, `runner/client/dialcore`, an autoconfigured `@Component`) owns the skill-reference
  format check, `validate(String) → Optional<String error>`. `TestCaseValidationService` and `BindingValidator` already use
  it.

Constraints: no API, DTO, DB or config change. Inference stays streaming (one pass, O(columns) state). Layering:
`service.domain.csv` may depend on runner-core (it already depends on `runner.dto`).

## Goals / Non-Goals

**Goals:**
- A plain-CSV export → import round trip restores `SKILL` the same way it restores `INTEGER`/`OBJECT`/`ARRAY`.
- One rule for "is this a skill reference", shared with validation (`SkillRefResolver.validate`), so inference and the
  `SKILL` format warning can never disagree.
- Move type inference into an injectable, unit-tested component.

**Non-Goals:**
- `FILE` inference from content; ZIP export for `SKILL`-only schemas; CSV header type hints.
- Any change to cell parsing, coercion (`SchemaTypeCoercer` / `SchemaChangeCoercer`), validation, ZIP rewriting, or
  runtime `|skill` resolution.
- Retyping columns whose definition the import keeps (APPEND into a non-empty schema, existing columns in MERGE).
  OVERRIDE already re-derives every column's type from the CSV and continues to do so, now including `SKILL`.

## Decisions

### D1 — Extract `CsvColumnTypeInferrer` (new class, `service.domain.csv`)
A `@Component @LogExecution` with `@RequiredArgsConstructor`, depending on `ObjectMapper` and `SkillRefResolver`:
- `SchemaFieldType inferCellType(String trimmedValue)`: the current body moved verbatim, plus the new `SKILL` branch.
- `static SchemaFieldType widen(SchemaFieldType a, SchemaFieldType b)`: the current `widenType` moved verbatim.

`CsvImportService` replaces its private `inferCellType` / `widenType` with calls to the injected component. Its two
`updateInferredTypes*` loops stay where they are, because they are tied to `ColumnBinding` / `CSVRecord` iteration and
the MERGE filter. The loops could fold into the component later, but that is out of scope.

*Why:* project rule: conversion logic belongs in injectable components, not private methods. Today inference is only
testable through `CsvImportService` with eight mocked collaborators. *Alternative rejected:* add a private SKILL check
plus a `SkillRefResolver` field to `CsvImportService`. That is smaller, but it grows an already 1.6k-line service and
keeps inference untestable on its own.

*Rejected for the existing helper:* extend `CsvCellParser.inferTypeName`. It is a separate, primitive-only inferrer that
production code never calls (only `CsvCellParserTest` does). Folding SKILL into it would create a second source of truth.
It is left untouched; removing it is unrelated cleanup.

### D2 — SKILL detection rule and branch order
A trimmed, non-blank cell is `SKILL` iff `skillRefResolver.validate(value).isEmpty()`. That covers the `skills/` prefix
(case-sensitive), a non-empty bucket plus at least one path segment, no empty `.`/`..` segments, no backslash or control
characters, and tolerance of one trailing `/`.

Order inside `inferCellType`: OBJECT → ARRAY → BOOLEAN → INTEGER → NUMBER → **SKILL** → STRING. A well-formed skill
reference can never match the earlier branches (it starts with `s`, contains `/`). Checking it last before the STRING
fallback keeps the existing branches byte-for-byte unchanged, and puts the extra work (a prefix check before any split)
only on values that would otherwise be STRING.

*Why reuse `validate`:* a column inferred `SKILL` then passes `SKILL` format validation by construction, so inference
cannot introduce warnings. *Alternative rejected:* a looser `startsWith("skills/")` check. It would infer `SKILL` for
`skills/bucket-only`, which `TestCaseValidationService` would then flag as malformed. That turns a clean import into a
warning-laden one.

### D3 — Widening is unchanged
`widen` keeps its rules: equal → same; `{INTEGER, NUMBER}` → `NUMBER`; anything else → `STRING`. So `SKILL`+`SKILL` →
`SKILL`, and `SKILL` + anything else → `STRING`. Blank cells are skipped by the callers, as today, so a blank cell never
demotes a `SKILL` column. A column that is blank on every row never enters `inferredTypes` and defaults to `STRING` in
`CsvSchemaFieldBuilder`, as today.

### D4 — Precedence and value handling are unchanged
- Precedence: manifest-declared field (`drivingFields`, applied in `CsvSchemaFieldBuilder.buildFromBindings` /
  `buildMergeDelta`) > `fileColumns` → `FILE` > inferred > `STRING` (both applied in `CsvSchemaFieldBuilder.resolveType`).
  No code change; the builder's javadoc is reviewed for any "never SKILL" wording. In a manifest-less ZIP, a column that
  holds `files/…` paths is still `FILE`. A `skills/…` column there is now `SKILL`.
- Existing columns: the APPEND path into a non-empty schema does no inference, and the MERGE path skips declared columns.
  So a declared `STRING` column holding skill references stays `STRING` there. OVERRIDE replaces membership and types
  from the CSV (baseline "OVERRIDE mode schema handling"), so the same column becomes `SKILL` on an OVERRIDE re-import.
- Values: cells are parsed as strings (`CsvCellParser.parseCell` leaves `skills/…` untouched). The fixup coerces with
  `SchemaTypeCoercer` (`SKILL` → `coerceToString`, identity), so data is unchanged and no re-validation is triggered. That
  is correct, because every non-blank cell is well-formed (D2) and inferred fields are `required: false`.
- ZIP rewriting does not depend on inference (see Context), so it is unaffected.

### Data model / API / transactions / errors
- **Data model:** none. A `SKILL` value is already a JSONB string, and the type lives in the JSONB `testCaseSchema`.
- **API contract:** unchanged shapes. The only observable change is that the preview's `autoDetectedSchema`, the
  preview's `detectedColumns[].inferredType` (derived from `autoDetectedSchema` in `buildDetectedColumns`) and the
  persisted schema can now contain `SKILL` for columns that previously came back `STRING`. The OpenAPI examples need no
  change.
- **Transactions:** unchanged. Inference is pure and in-memory inside the existing import transaction.
- **Error handling:** `validate` never throws for a non-null input, and callers never pass null or blank values, so there
  is no new failure mode. The existing `JacksonException` handling for OBJECT/ARRAY moves unchanged.

### Spec reconciliation
- `test-cases`:
  - MODIFIED "SKILL columns in CSV import and export": replace the no-inference sentence and scenario with the
    inference rule and scenarios.
  - MODIFIED "CSV bulk upload with schema detection": the "Auto-detect type inference" scenario lists `SKILL`.
- `dial-skill-ref`: MODIFIED first requirement. CSV cell handling stays STRING-like, and schema detection may infer
  `SKILL`.
- The `test-cases` "Type System Reference" table sits outside any requirement, so delta sync cannot reach it. It is
  edited by hand in the baseline in task 3.3 (already applied in the working tree): `SKILL` → inferred, and the stale
  `OBJECT`/`ARRAY` "No" rows corrected to match `CsvColumnTypeInferrer`. Until the delta specs are synced, the baseline
  is briefly inconsistent: the table says `SKILL` is inferred, but the baseline "SKILL columns in CSV import and export"
  requirement still says it is not. So the table edit MUST land in the same commit as the archive-time delta sync. At
  archive, confirm that the sync neither reverts nor duplicates the edited table rows.
- The archived change `2026-10-27-add-skill-schema-type` is not edited. This change's specs supersede its D6 when it is
  archived.

## Risks / Trade-offs

- [A free-text column that happens to hold only `skills/…` values is now typed `SKILL`] → `SKILL` behaves like `STRING`
  except for the format warning (never fires, D2) and `|skill` canonicalisation, which trims surrounding whitespace and
  one trailing `/` for well-formed values: a hintless placeholder bound to a newly inferred `SKILL` column sends
  `skills/b/x` for a stored `skills/b/x/`. The author can retype the column; columns kept by APPEND/MERGE are never
  retyped.
- [The preview now shows `SKILL` where the FE previously saw `STRING`] → The FE already supports `SKILL` (#268). Call
  this out in the release notes.
- [A malformed reference in an exported `SKILL` column (for example `skills/bucket`, already a stored warning) demotes
  the whole column to `STRING` on re-import] → An accepted edge, consistent with how a single non-numeric cell demotes an
  `INTEGER` column. The value itself survives verbatim.
- [Divergence between `inferTypeName` (dead) and the real inferrer persists] → Out of scope; noted here for a future
  cleanup.

## Migration Plan

No data migration and no flag. The change is deployed with the next backend release. Datasets imported before the
change keep their persisted schema. Rollback means reverting the commit; datasets imported with `SKILL` in the meantime
keep a valid `SKILL` type.

## Open Questions

None. The approach (content inference rather than ZIP-for-SKILL) was confirmed with the requester.
