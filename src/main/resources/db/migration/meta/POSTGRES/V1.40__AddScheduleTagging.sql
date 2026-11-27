-- Schedule tagging for runs started by the DIAL Core scheduler.
--
-- test_suite_runs.schedule_id: the id of the schedule that started the run, null for manual runs. There is
-- deliberately NO foreign key: a run keeps the id of its schedule after the schedule is deleted, which is what
-- makes the tag usable for audit. The partial index serves "runs of schedule X" and skips the (vast) majority
-- of manual runs.
--
-- test_suite_schedules.schedule_ref: an EF-generated reference carried in the schedule's callback URL
-- (?scheduleRef=...) and resolved back to the schedule when the scheduler fires. It exists because the
-- scheduler's own id is not known until the create call has returned. Nullable so rows created before this
-- migration stay valid; they get a reference the first time they are edited.
--
-- Both columns are nullable without a default, which PostgreSQL adds without rewriting the table.
ALTER TABLE test_suite_runs
    ADD COLUMN schedule_id VARCHAR(255);

CREATE INDEX idx_test_suite_runs_schedule
    ON test_suite_runs (schedule_id, created_at_ms DESC)
    WHERE schedule_id IS NOT NULL;

ALTER TABLE test_suite_schedules
    ADD COLUMN schedule_ref VARCHAR(36);

CREATE UNIQUE INDEX uq_test_suite_schedules_ref
    ON test_suite_schedules (schedule_ref)
    WHERE schedule_ref IS NOT NULL;
