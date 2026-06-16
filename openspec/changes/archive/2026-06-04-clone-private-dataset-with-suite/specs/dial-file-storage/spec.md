## ADDED Requirements

### Requirement: Copy files between dataset folders
The system SHALL provide a service operation `FileService.copyFilesBetweenDatasets(sourceDatasetId, targetDatasetId)` that copies every file from the source dataset's DIAL storage folder (`{bucket}/datasets/{sourceDatasetId}/`) to the target dataset's folder (`{bucket}/datasets/{targetDatasetId}/`). The operation mirrors the existing suite-to-suite copy: it SHALL be best-effort (skip and log inaccessible files, continue), SHALL NOT require the target dataset to exist in the database, and SHALL return the list of successfully copied filenames. It is intended to run before the database transaction during a dataset clone.
Status: **Planned**

#### Scenario: Dataset-scoped files are copied
- **WHEN** the source dataset folder contains `a.csv` and `b.json` and `copyFilesBetweenDatasets(source, target)` is invoked
- **THEN** after the call the target dataset folder SHALL contain `a.csv` and `b.json` with identical content
- **AND** the operation SHALL return `["a.csv", "b.json"]`

#### Scenario: Inaccessible file is skipped gracefully
- **WHEN** a file in the source dataset folder cannot be downloaded
- **THEN** system SHALL log a warning and continue copying the remaining files without throwing

## Implementation notes
- `service.domain.FileService.copyFilesBetweenDatasets` mirrors `copyFilesBetweenSuites` (`FileService.java`) using `buildDatasetFolderPath`. Cleanup of a partially-copied target folder uses the existing `deleteAllByDatasetId`.
