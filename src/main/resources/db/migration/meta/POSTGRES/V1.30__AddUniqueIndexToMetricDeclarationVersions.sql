-- (metric_declaration_id, schema_version) is a per-declaration sequence: no declaration may carry
-- two rows for the same schema_version. Enforced as a UNIQUE INDEX rather than a UNIQUE constraint
-- so the second column keeps its DESC ordering: SELECT DISTINCT ON (metric_declaration_id) ...
-- ORDER BY metric_declaration_id, schema_version DESC can only be served by an index whose second
-- column is DESC, and a UNIQUE constraint cannot declare one. This index therefore replaces the
-- non-unique index created in V1.9 instead of being added alongside it.
DROP INDEX IF EXISTS idx_metric_declaration_versions_declaration_version;

CREATE UNIQUE INDEX IF NOT EXISTS uq_metric_declaration_versions_declaration_version
    ON metric_declaration_versions (metric_declaration_id, schema_version DESC);
