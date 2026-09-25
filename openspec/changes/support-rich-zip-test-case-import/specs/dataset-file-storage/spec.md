## ADDED Requirements

### Requirement: ZIP-import file writes follow dataset file rules
Files written into a dataset by a test-case ZIP import (see the `test-case-zip-archive` capability) SHALL be stored at `{efBucket}/datasets/{datasetId}/{filename}` and SHALL obey the same rules as the dataset upload endpoint:
- the file size limit `dial.file-storage.max-file-size-bytes`;
- the filename rules (allowed characters, max length 255, no leading/trailing whitespace), applied by sanitizing the archive filename rather than rejecting it;
- the per-dataset file count limit `dial.file-storage.max-files-per-dataset`.

The count limit SHALL count only files the import newly creates; overwriting a same-name file SHALL NOT count.

Unlike the upload endpoint, which rejects a duplicate filename with HTTP 400, a ZIP import SHALL overwrite an existing dataset file with the same name. Such files SHALL be listed, downloaded and deleted through the dataset file endpoints like any other dataset file.

Status: **Planned**

#### Scenario: ZIP import file above the size limit
- **WHEN** a ZIP import references an archive file larger than `dial.file-storage.max-file-size-bytes`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and SHALL NOT keep any file written by that import

#### Scenario: ZIP import filename is sanitized
- **WHEN** a ZIP import references an archive file named `report#final.pdf`
- **THEN** the dataset file SHALL be stored under a name matching the dataset filename rules (e.g. `report_final.pdf`) and SHALL appear in `GET /api/v1/datasets/{datasetId}/files`

#### Scenario: ZIP import overwrites instead of rejecting a duplicate name
- **WHEN** a dataset already holds `report.pdf` and a ZIP import references an archive file named `report.pdf`
- **THEN** the dataset's `report.pdf` SHALL be overwritten with the archive's content and no HTTP 400 duplicate-filename error SHALL be returned

#### Scenario: ZIP import would exceed the file count limit
- **WHEN** a ZIP import would newly create more files than the dataset's remaining capacity under `dial.file-storage.max-files-per-dataset`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` before writing any file
