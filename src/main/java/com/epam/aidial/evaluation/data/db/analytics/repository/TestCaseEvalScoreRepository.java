package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import java.util.List;

public interface TestCaseEvalScoreRepository {

    void saveAll(List<TestCaseEvalScore> scores);
}
