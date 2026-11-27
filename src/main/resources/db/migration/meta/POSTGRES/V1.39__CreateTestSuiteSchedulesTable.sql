-- Link between a test suite and a schedule that lives in the DIAL Core scheduler application.
-- The scheduler's list response does not carry the target URL and cannot be filtered by suite, so EF
-- keeps only this mapping (schedule_id -> test_suite_id) plus audit columns; cron, run configuration,
-- state and history stay in the scheduler.
-- Deleting a suite removes its mapping rows (the schedule itself is not removed from the scheduler).
CREATE TABLE test_suite_schedules (
    schedule_id    VARCHAR(255) NOT NULL PRIMARY KEY,
    test_suite_id  VARCHAR(36)  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    created_at     BIGINT       NOT NULL,
    CONSTRAINT fk_test_suite_schedules_suite FOREIGN KEY (test_suite_id)
        REFERENCES test_suites (id) ON DELETE CASCADE
);

CREATE INDEX idx_test_suite_schedules_suite
    ON test_suite_schedules (test_suite_id, created_at DESC);
