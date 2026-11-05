## Why

Issue [#288](https://github.com/epam/ai-dial-admin-evaluation-framework-backend/issues/288): exporting a dataset with a
`SKILL` column (and no `FILE` column) produces a plain CSV, and importing that CSV into a new dataset turns the column
into `STRING` — while an `INTEGER` column survives the same round trip. #268 required `SKILL` values **and types** to be
preserved through CSV import/export; today that only holds when a `FILE` column forces a ZIP export whose
`manifest.json` carries the type.

## What Changes

**Current state (Implemented).** A plain CSV carries no type metadata. On import every non-string type is re-inferred
from cell content during schema auto-detection (`CsvImportService.inferCellType` / `widenType`): JSON object → `OBJECT`,
JSON array → `ARRAY`, `true`/`false` → `BOOLEAN`, whole number → `INTEGER`, decimal → `NUMBER`, otherwise `STRING`;
a column with mixed cell types widens to `STRING` (`INTEGER`+`NUMBER` → `NUMBER`). `FILE` is never inferred — it comes
only from a ZIP manifest or the manifest-less `files/…` column hint. `SKILL` was deliberately excluded from inference
(archived change `2026-10-27-add-skill-schema-type`, design D6; `test-cases` scenario "No content-based SKILL inference").

**Change (Planned).**
- CSV schema auto-detection SHALL infer `SKILL` for a column whose every non-blank cell is a well-formed skill reference
  (`skills/{bucket}/{path}`, the existing `SkillRefResolver.validate` rule). A column mixing skill references with any
  other value widens to `STRING` through the existing widening rule.
- Applies everywhere inference already runs and nowhere else: import preview `autoDetectedSchema`, OVERRIDE import,
  APPEND into an empty schema, the MERGE new-column delta, ZIP import **without** a manifest, and ZIP import with a
  manifest for the CSV columns the manifest does not list. Columns whose definition
  the import keeps (APPEND into a non-empty schema, existing columns in MERGE) are never retyped; OVERRIDE re-derives every
  column's type from the CSV, as it does today for every type. A manifest-declared type and the `fileColumns` → `FILE`
  hint keep precedence.
- Inference logic (`inferCellType` + `widenType`) moves out of `CsvImportService` into a new injectable
  `service.domain.csv.CsvColumnTypeInferrer` component, per the "specialized components, not private methods" rule.
- Reverses D6 of `add-skill-schema-type`; the `test-cases` "SKILL columns in CSV import and export" requirement and the
  generic "Auto-detect type inference" scenario are updated accordingly. Not **BREAKING**: no API, DTO, DB or config
  change; a `skills/…`-only column previously detected as `STRING` is now detected as `SKILL`.

**Goals.** `SKILL` survives a plain-CSV export → import round trip exactly like `INTEGER`/`OBJECT`/`ARRAY`.

**Non-goals.** Content inference for `FILE`; exporting `SKILL`-only datasets as ZIP; type hints in CSV headers; any
change to how `SKILL` cell values are parsed, coerced, validated or resolved.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `test-cases`: "SKILL columns in CSV import and export" — `SKILL` is now inferred from well-formed skill references
  (replaces "No content-based SKILL inference"); "CSV bulk upload with schema detection" — the "Auto-detect type
  inference" scenario gains the `SKILL` rule.
- `dial-skill-ref`: clarify in the "SKILL schema type is a string-backed DIAL skill reference" requirement that CSV
  *cell handling* stays `STRING`-like while CSV *schema detection* may infer `SKILL` (see `test-cases`).

## Impact

- **Code:** `service/domain/CsvImportService.java` (delegates inference); new
  `service/domain/csv/CsvColumnTypeInferrer.java` (`@Component @LogExecution`, depends on runner-core
  `SkillRefResolver` and `ObjectMapper`). `CsvSchemaFieldBuilder` unchanged in code (javadoc only).
- **API / data / security:** none. Preview may now report `SKILL` in `autoDetectedSchema` and in
  `detectedColumns[].inferredType`; the FE already supports the `SKILL` type (#268). No migration, no configuration
  property.
- **Risks:** a column the author meant as free text but which contains only `skills/…` values is now typed `SKILL`.
  Low impact — `SKILL` behaves like `STRING` everywhere except format validation (every cell is well-formed by
  construction, so no warnings) and `|skill` placeholder canonicalisation (trims surrounding whitespace and one trailing
  `/` for well-formed values, so a stored `skills/b/x/` is sent as `skills/b/x`); the author can
  retype the column. Columns kept by APPEND/MERGE are never retyped; an OVERRIDE re-import of a declared `STRING` column
  holding only skill references turns it into `SKILL`, consistent with how OVERRIDE re-derives every other type.
- **Rollout:** ships with the next backend release; no flags, no data backfill. Already-imported datasets are unaffected.
- **Test plan:** unit — new `CsvColumnTypeInferrerTest` (regression for existing types + `SKILL` well-formed / malformed /
  mixed), `CsvImportServiceSchemaTest` (auto-detect + MERGE delta), `CsvSchemaFieldBuilderTest`; functional —
  `SkillFieldFunctionalTests` (plain-CSV round trip, inverted no-inference test, mixed column → `STRING`, preview),
  plus regression runs of CSV import mode, plain-CSV golden and ZIP round-trip suites.
