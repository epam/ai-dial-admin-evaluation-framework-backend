package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummaryScore;
import java.util.List;

public interface EvalSummaryScoreRepository {

    void saveAll(List<EvalSummaryScore> scores);
}
