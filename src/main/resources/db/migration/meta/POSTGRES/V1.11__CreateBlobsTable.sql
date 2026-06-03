CREATE TABLE blobs (
    id            VARCHAR(36) PRIMARY KEY,
    test_suite_id VARCHAR(36) NOT NULL,
    oid           BIGINT NOT NULL,
    filename      VARCHAR(255),
    content_type  VARCHAR(255),
    size_bytes    BIGINT NOT NULL,
    created_by    VARCHAR(255) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    CONSTRAINT fk_blobs_test_suite FOREIGN KEY (test_suite_id) REFERENCES test_suites(id) ON DELETE CASCADE
);

CREATE INDEX idx_blobs_test_suite_id ON blobs(test_suite_id);
