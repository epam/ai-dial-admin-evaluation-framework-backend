package com.epam.aidial.evaluation.functional;

import com.epam.aidial.evaluation.functional.config.persistence.TestPersistenceService;
import com.epam.aidial.evaluation.service.domain.job.ActiveRunRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FunctionalTests {

    private static final long DRAIN_TIMEOUT_MS = 30_000L;
    private static final long DRAIN_POLL_INTERVAL_MS = 50L;

    @Autowired
    private TestPersistenceService persistenceService;

    @Autowired(required = false)
    private ActiveRunRegistry activeRunRegistry;

    @BeforeAll
    void beforeAllTests() {
        persistenceService.dumpDb();
    }

    @AfterEach
    void afterEachTest() {
        // Tests that POST /runs dispatch work to the test suite run job executor via afterCommit.
        // If a test doesn't explicitly await terminal state, the snapshot tx
        // (REPEATABLE READ, holds row locks on meta tables) can still be running when
        // restoreDb() issues DROP SCHEMA public CASCADE — which needs ACCESS EXCLUSIVE
        // and deadlocks against those row locks (SQLSTATE 40P01 → PessimisticLockingFailureException).
        drainActiveRuns();
        persistenceService.restoreDb();
    }

    @AfterAll
    void afterAllTests() {
        persistenceService.cleanupResources();
    }

    private void drainActiveRuns() {
        if (activeRunRegistry == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (activeRunRegistry.activeCount() == 0) {
                return;
            }
            try {
                Thread.sleep(DRAIN_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn(
                "Active runs did not drain within {}ms; remaining={}",
                DRAIN_TIMEOUT_MS,
                activeRunRegistry.activeCount());
    }
}
