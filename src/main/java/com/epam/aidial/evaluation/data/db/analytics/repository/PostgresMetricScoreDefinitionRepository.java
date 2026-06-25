package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.METRIC_SCORE_DEFINITION;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.data.db.analytics.mapper.MetricScoreDefinitionRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreDefinition;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresMetricScoreDefinitionRepository implements MetricScoreDefinitionRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final MetricScoreDefinitionRecordMapper recordMapper;

    @Override
    public List<MetricScoreDefinition> findApplicable(UUID testSuiteId) {
        return dsl.selectFrom(METRIC_SCORE_DEFINITION)
                .where(METRIC_SCORE_DEFINITION.TYPE.eq(MetricScoreConstants.TYPE_DEFAULT))
                .or(METRIC_SCORE_DEFINITION
                        .TYPE
                        .eq(MetricScoreConstants.TYPE_TEST_SUITE)
                        .and(METRIC_SCORE_DEFINITION.TARGET_ID.eq(testSuiteId.toString())))
                .orderBy(METRIC_SCORE_DEFINITION.NAME.asc())
                .fetch(recordMapper::map);
    }
}
