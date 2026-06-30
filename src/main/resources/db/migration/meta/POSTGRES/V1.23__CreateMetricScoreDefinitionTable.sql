CREATE TABLE metric_score_definition (
    id              VARCHAR(36)   NOT NULL PRIMARY KEY,
    type            VARCHAR(32)   NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    description     VARCHAR(1024),
    expression      JSONB         NOT NULL,
    target_id       VARCHAR(36)
);

-- target_id is NULL for DEFAULT definitions; NULLs are distinct in a standard unique index, so a
-- single unique constraint over (type, name, target_id) would not prevent duplicate GLOBAL rows.
-- Two partial unique indexes enforce uniqueness for both the null-target and targeted cases.
CREATE UNIQUE INDEX uq_metric_score_definition_global
    ON metric_score_definition (type, name)
    WHERE target_id IS NULL;

CREATE UNIQUE INDEX uq_metric_score_definition_targeted
    ON metric_score_definition (type, name, target_id)
    WHERE target_id IS NOT NULL;

CREATE INDEX idx_metric_score_definition_type_target
    ON metric_score_definition (type, target_id);
