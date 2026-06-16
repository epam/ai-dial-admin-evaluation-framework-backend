ALTER TABLE revalidation_tasks
    ADD COLUMN coerced_cell_count BIGINT NOT NULL DEFAULT 0;
