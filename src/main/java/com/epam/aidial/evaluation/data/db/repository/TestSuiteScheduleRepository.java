package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.Optional;
import java.util.UUID;

public interface TestSuiteScheduleRepository {

    /** Inserts the mapping row; {@code createdAt} is taken from the argument, not from a transaction context. */
    TestSuiteSchedule save(TestSuiteSchedule schedule);

    Optional<TestSuiteSchedule> findById(String scheduleId);

    /** Finds the schedule that owns a callback reference; empty for a blank or unknown reference. */
    Optional<TestSuiteSchedule> findByRef(String scheduleRef);

    /**
     * Sets the callback reference of an existing schedule.
     *
     * @return {@code true} when the schedule exists
     */
    boolean updateRef(String scheduleId, String scheduleRef);

    /** Newest first; the sort of the request is ignored. */
    Page<TestSuiteSchedule> findPageByTestSuiteId(UUID testSuiteId, PageRequest pageRequest);

    /**
     * Deletes the mapping row.
     *
     * @return {@code true} when a row was removed
     */
    boolean deleteById(String scheduleId);
}
