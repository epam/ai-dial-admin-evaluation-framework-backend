-- Add provider_id to metric_declarations (required for provider-synced catalog)
-- Use temporary default so we can add NOT NULL before deleting seeded rows
ALTER TABLE metric_declarations
    ADD COLUMN provider_id VARCHAR(255) NOT NULL DEFAULT 'legacy';

-- Remove previously seeded (stub) rows so catalog contains only provider-synced metrics
DELETE FROM metric_declarations;

-- Drop default so new rows must supply provider_id
ALTER TABLE metric_declarations
    ALTER COLUMN provider_id DROP DEFAULT;

-- Enforce unique (provider_id, name) so same metric name from different providers is distinct
CREATE UNIQUE INDEX IF NOT EXISTS uq_metric_declarations_provider_id_name
    ON metric_declarations(provider_id, name);
