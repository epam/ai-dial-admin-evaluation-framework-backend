package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteScheduleRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Resolves the {@code scheduleRef} a schedule's callback URL carries to the id of the schedule that owns it, so a run
 * started by the scheduler can be tagged. Only depends on the schedule domain's own repository, so the run service can
 * use it without a dependency cycle.
 *
 * <p>Never fails: an unknown or foreign reference is ignored and the run simply stays untagged. Any authenticated caller
 * can pass a {@code scheduleRef}; requiring that the schedule belongs to the suite being run limits a forged value to
 * mislabelling a run in a suite the caller can already run.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class ScheduleRefResolver {

    private final TestSuiteScheduleRepository repository;

    /**
     * Returns the schedule id for a reference, or empty when there is nothing to tag.
     *
     * @param scheduleRef the {@code scheduleRef} request parameter; blank means a manual run
     * @param suiteId the suite being run; the schedule must belong to it
     */
    public Optional<String> resolveScheduleId(String scheduleRef, UUID suiteId) {
        if (StringUtils.isBlank(scheduleRef)) {
            return Optional.empty();
        }
        final Optional<TestSuiteSchedule> schedule = repository.findByRef(scheduleRef);
        if (schedule.isEmpty()) {
            log.warn(
                    "Ignoring unknown scheduleRef '{}' on a run of suite {}; the run is not tagged",
                    scheduleRef,
                    suiteId);
            return Optional.empty();
        }
        if (!suiteId.equals(schedule.get().getTestSuiteId())) {
            log.warn(
                    "Ignoring scheduleRef '{}' on a run of suite {}: it belongs to a schedule of suite {}; the run is not tagged",
                    scheduleRef,
                    suiteId,
                    schedule.get().getTestSuiteId());
            return Optional.empty();
        }
        return Optional.of(schedule.get().getScheduleId());
    }
}
