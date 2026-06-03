package com.epam.aidial.evaluation.functional.config.persistence;

public interface TestPersistenceService {

    void dumpDb();

    void restoreDb();

    void cleanupResources();
}
