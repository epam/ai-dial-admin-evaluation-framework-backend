## MODIFIED Requirements

### Requirement: ZIP manifest
Every ZIP export SHALL include a `manifest.json` holding:
- a `formatVersion` (currently `1`);
- the dataset's full `testCaseSchema`: every field with its `name`, `type`, `perTurn`, `required`, `displayName` and `description`. Field `id`s SHALL NOT be written: an id identifies a field within one dataset schema only and has no meaning in another dataset;
- a `files` list pairing each archive file path with the file reference it was exported from.

The manifest SHALL be written even when no file is copied into the archive. When export produces a ZIP rather than a CSV is defined by the `test-cases` capability and is unchanged.

On import the manifest SHALL be optional. An archive without a manifest SHALL import (see "ZIP import without a manifest"). A field `id` present in an imported manifest SHALL be ignored; the target dataset's field ids are resolved by name (see the `test-cases` requirement "Import persists the schema through the dataset update rules").

Status: **Planned**

#### Scenario: Export always writes the manifest
- **WHEN** a client exports a dataset as ZIP
- **THEN** the archive SHALL contain `manifest.json` with `formatVersion: 1`, a `testCaseSchema` equal to the dataset's current `testCaseSchema` minus field `id`s, and one `files` entry per archive file naming its source reference

#### Scenario: Manifest field ids are ignored on import
- **WHEN** a client imports a ZIP whose manifest fields carry `id` values that do not belong to the target dataset
- **THEN** the import SHALL NOT fail on those ids; each field SHALL take the `id` of the target's same-named field, or a new `id` when none exists

#### Scenario: Unsupported manifest version
- **WHEN** a client imports a ZIP whose `manifest.json` carries a `formatVersion` the system does not support
- **THEN** the system SHALL return HTTP 400 and SHALL NOT upload any file

#### Scenario: Malformed manifest
- **WHEN** a client imports a ZIP whose `manifest.json` is not valid JSON or does not match the manifest structure
- **THEN** the system SHALL return HTTP 400 and SHALL NOT upload any file

#### Scenario: Invalid field definition in the manifest
- **WHEN** a manifest's `testCaseSchema` is missing, contains a `null` entry, a field that violates the field-definition rules used by the schema API (e.g. a name with `:`, a missing `type`), or two fields with the same name; or its `files` list contains a `null` entry or an entry without a `path`
- **THEN** the system SHALL return HTTP 400 naming the offending field or entry, and SHALL NOT upload any file
