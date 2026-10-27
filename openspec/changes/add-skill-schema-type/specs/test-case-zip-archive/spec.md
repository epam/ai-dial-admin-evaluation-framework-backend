# Test Case ZIP Archive — delta for `add-skill-schema-type`

## ADDED Requirements

### Requirement: SKILL columns are not packed and keep their type through the ZIP round trip
`SKILL` values SHALL NOT be treated as file references by ZIP export or import: a suite/dataset whose only reference-typed columns are `SKILL` SHALL NOT trigger ZIP export (CSV is returned); in a ZIP archive produced because of `FILE` columns, `SKILL` cells SHALL be written verbatim and SHALL NOT be collected, downloaded or added as archive entries; on import, `SKILL` cells SHALL NOT be rewritten, uploaded or placed in storage. The manifest SHALL record `SKILL` columns with type `SKILL`, and on import the manifest-declared schema SHALL restore the `SKILL` type, so export → import is repeatable without change to values or types.

Status: **Implemented**
Implementation notes: no ZIP code change — `ZipExportService.hasFileFields`, `ZipExportFileCollector` and `ZipImportColumnTypeResolver.eligibleForRewrite` are FILE-gated by design; `ZipManifestSerializer` writes the enum by name. Tests: `ZipExportServiceTest`, `ZipImportColumnTypeResolverTest`, `ZipManifestSerializerTest`, `SkillFieldFunctionalTests` (ZIP round trip).

#### Scenario: SKILL-only export is CSV
- **WHEN** a dataset has a `SKILL` column and no `FILE` column and is exported
- **THEN** the response SHALL be a CSV, not a ZIP

#### Scenario: Mixed FILE and SKILL export
- **WHEN** a dataset has a `FILE` column and a `SKILL` column and is exported
- **THEN** the ZIP SHALL contain entries only for the `FILE` values, the `SKILL` cells SHALL be verbatim in the CSV, and the manifest SHALL list the `SKILL` column with type `SKILL`

#### Scenario: Import restores SKILL type and does not rewrite
- **WHEN** such an archive is imported with OVERRIDE
- **THEN** the dataset schema SHALL have the `SKILL` column with type `SKILL` and the cell values SHALL equal the exported strings, with no storage upload for them

#### Scenario: Round trip is repeatable
- **WHEN** the imported dataset is exported again
- **THEN** the `SKILL` cells and manifest type SHALL be identical to the first export
