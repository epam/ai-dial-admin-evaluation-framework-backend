package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.List;

/**
 * Per-run sink for {@link TestCaseRunResult} rows produced by {@link TestCaseRunner}. An instance is
 * scoped to a single run for its whole lifetime — no separate buffer/handle parameter. DB-free by
 * contract so a standalone runner can supply an alternate implementation (e.g. in-memory, CSV) owning
 * its own buffering/flush policy; the EF backend's Postgres-backed implementation lives outside this
 * module.
 */
public interface ResultBatchWriter {

    void addResults(List<TestCaseRunResult> results);

    void flush();
}
